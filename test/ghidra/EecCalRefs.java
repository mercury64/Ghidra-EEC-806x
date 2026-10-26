// Lists the calibration data that the code of an EEC-IV program refers to, when the base
// registers hold the values that the init routines give them. It runs every basic block, and
// every path that starts at a basic block, in Ghidra's p-code emulator
// (ghidra.pcode.emu.PcodeEmulator), and writes each address in a given range that the code forms,
// loads, or passes to a lookup routine.
//
// It uses the method of test/ghidra/EecEmulatorCheck.java (refs=), for all addresses of a range
// instead of a list of functions:
//   * Each basic block runs twice: from the RAM after init with the other bytes 0, then with
//     every RAM byte that is 0 after init set to 2. A word value or a load address that is the
//     same in both runs depends only on constants and on registers that init set (for example
//     AD3W R36,Rfc,#0x500 when init loaded the base register Rfc).
//   * A path starts at each basic block and runs until the code returns from the function of the
//     start block, or for steps= instructions. It follows calls; a return goes to the
//     fall-through of the call (also for a call to another bank, where the code returns with
//     POPP and RET). It runs twice, with the same two RAM states. At each entry into a lookup
//     routine it records the table register, the instruction that last wrote it and the calls
//     that lead there. This finds tables whose address is formed in an earlier block.
//
// Arguments (key=value). Addresses are banked hex: bank * 0x10000 + 16-bit address.
//   out=<path>        result rows, tab-separated (required)
//   listing=<path>    optional: one row per instruction (address, function, block, bytes,
//                     mnemonic, operands, flows, fall-through)
//   init=<a>,<b>      subroutines to run first, in this order, each until it returns
//   ram=<a>:<v>,...   RAM bytes to set after init (hex)
//   lookups=<entry>:<reg>,...  lookup entries and the register that holds the 16-bit table
//                     address in the data bank at entry (for example 87dd2:36)
//   range=<lo>-<hi>   addresses to report, inclusive (default 12000-1ffff)
//   track=<reg>,...   registers to report at each lookup entry: the word value and the
//                     instruction that last wrote it (for example 34,36,38 for the table
//                     positions and x)
//   steps=<n>         instructions for each path run (decimal, default 3000)
//   paths=true|false  run the paths (default true)
//
// Result rows (header: kind at function value value2 detail), values hex:
//   value   at writes the word value (a banked address in range) to the address value2, in
//           both block runs
//   load    at loads value2 bytes from value, the same address in both block runs
//   index   at loads from value in the first block run and from value2 in the second (the
//           address depends on another register: a list or table index)
//   lookup  a path reaches the lookup entry value2 with the table address value; at is the call
//           of the lookup; detail: run (A or B), the instruction that last wrote the table
//           register (former), the calls from the start block (chain), the start block, and
//           for each track= register its value and last writer (r38=0010@83b7c)
//   pload   a path loads from value to value2 (lowest and highest address of the loads of one
//           instruction) inside the call at, the first call of the path that is still open;
//           detail: run, the loading instruction, count
//
// Limits: as EecEmulatorCheck.java. Hardware registers read 0; a divide by 0 or an operation the
// emulator does not have (NORML) stops a run; values are not followed from one block to the next
// except along a path; a path takes the branches that the RAM state gives it.
//@category EEC

import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import ghidra.app.script.GhidraScript;
import ghidra.pcode.emu.PcodeEmulationCallbacks;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeArithmetic;
import ghidra.pcode.exec.PcodeArithmetic.Purpose;
import ghidra.pcode.exec.PcodeExecutorStatePiece;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.PcodeProgram;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.block.CodeBlock;
import ghidra.program.model.block.CodeBlockIterator;
import ghidra.program.model.block.SimpleBlockModel;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.symbol.FlowType;

public class EecCalRefs extends GhidraScript {

	private static final int RAM_SIZE = 0x2000;
	private static final int ROM_START = 0x2000;
	private static final long STACK_TOP = 0x1f00;
	private static final long RETURN_MARK = 0x1ffe;
	private static final int INIT_STEPS = 100000;
	private static final int BLOCK_STEPS = 64;
	private static final int LOOKUP_SPAN = 0x80;

	private Map<String, String> args;
	private PcodeEmulator emu;
	private PcodeThread<byte[]> thread;
	private PcodeArithmetic<byte[]> arith;
	private AddressSpace ram;
	private Register stackPointer;
	private Register dataBank;
	private final Recorder recorder = new Recorder();
	private byte[] ramAfterInit;
	private byte[] ramFilled;
	private long rangeLo = 0x12000;
	private long rangeHi = 0x1ffff;
	private final Map<Long, Long> lookups = new LinkedHashMap<>();   // entry -> table register
	private final List<Long> tracked = new ArrayList<>();
	private final Set<Long> watched = new LinkedHashSet<>();          // registers with a last writer
	private final Set<String> rows = new LinkedHashSet<>();
	private final Map<String, Integer> failures = new TreeMap<>();
	private int pathRuns;
	private int pathSteps;

	@Override
	protected void run() throws Exception {
		args = parseArgs(getScriptArgs());
		String[] range = args.getOrDefault("range", "12000-1ffff").split("-");
		rangeLo = Long.parseLong(range[0], 16);
		rangeHi = Long.parseLong(range[1], 16);
		for (String item : list("lookups")) {
			String[] parts = item.split(":");
			lookups.put(Long.parseLong(parts[0], 16), Long.parseLong(parts[1], 16));
		}
		for (String item : list("track")) {
			tracked.add(Long.parseLong(item, 16));
		}
		watched.addAll(lookups.values());
		watched.addAll(tracked);
		setUpEmulator();
		for (String item : list("init")) {
			long entry = Long.parseLong(item, 16);
			setStackPointer(STACK_TOP - 2);
			writeMemory(STACK_TOP - 2, 2, RETURN_MARK);
			startAt(entry);
			int steps = runUntil((entry & ~0xffffL) | RETURN_MARK, INIT_STEPS);
			println(String.format("init %05x: returned after %d instructions", entry, steps));
		}
		for (String item : list("ram")) {
			String[] parts = item.split(":");
			writeMemory(Long.parseLong(parts[0], 16), 1, Long.parseLong(parts[1], 16));
		}
		ramAfterInit = readRam();
		ramFilled = ramAfterInit.clone();
		for (int i = 2; i < ramFilled.length; i++) {
			ramFilled[i] = ramFilled[i] == 0 ? 2 : ramFilled[i];
		}
		printBaseRegisters();
		if (args.containsKey("listing")) {
			writeListing(Path.of(args.get("listing")));
		}
		classifyBlocks();
		if (!"false".equals(args.getOrDefault("paths", "true"))) {
			runPaths();
		}
		Path out = Path.of(require("out"));
		Files.createDirectories(out.toAbsolutePath().getParent());
		List<String> all = new ArrayList<>();
		all.add("kind\tat\tfunction\tvalue\tvalue2\tdetail");
		all.addAll(rows);
		Files.write(out, all, StandardCharsets.UTF_8);
		println(String.format("wrote %d rows to %s; path runs %d, path instructions %d; " +
			"stopped early: %s", rows.size(), out, pathRuns, pathSteps,
			failures.isEmpty() ? "none" : failures.toString()));
	}

	// ---- arguments ----

	private Map<String, String> parseArgs(String[] raw) {
		Map<String, String> parsed = new HashMap<>();
		for (String arg : raw) {
			int eq = arg.indexOf('=');
			if (eq <= 0) {
				throw new IllegalArgumentException("argument is not key=value: " + arg);
			}
			parsed.put(arg.substring(0, eq), arg.substring(eq + 1));
		}
		return parsed;
	}

	private String require(String key) {
		String value = args.get(key);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("missing argument " + key + "=...");
		}
		return value;
	}

	private List<String> list(String key) {
		List<String> items = new ArrayList<>();
		for (String item : args.getOrDefault(key, "").split(",")) {
			if (!item.isBlank()) {
				items.add(item.trim());
			}
		}
		return items;
	}

	private boolean inRange(long a) {
		return a >= rangeLo && a <= rangeHi;
	}

	// ---- emulator state ----

	private void setUpEmulator() throws Exception {
		emu = new PcodeEmulator(currentProgram.getLanguage(), recorder);
		arith = emu.getArithmetic();
		ram = currentProgram.getAddressFactory().getDefaultAddressSpace();
		stackPointer = currentProgram.getCompilerSpec().getStackPointer();
		dataBank = currentProgram.getRegister("dbank");
		for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
			if (block.isInitialized()) {
				byte[] bytes = new byte[(int) block.getSize()];
				block.getBytes(block.getStart(), bytes);
				emu.getSharedState().setVar(block.getStart(), bytes.length, false, bytes);
			}
		}
		thread = emu.newThread();
	}

	private void writeMemory(long address, int size, long value) {
		emu.getSharedState().setVar(ram.getAddress(address), size, false,
			arith.fromConst(value, size));
	}

	private long readMemory(long address, int size) {
		byte[] bytes =
			emu.getSharedState().getVar(ram.getAddress(address), size, false, Reason.INSPECT);
		return arith.toLong(bytes, Purpose.INSPECT);
	}

	private byte[] readRam() {
		return emu.getSharedState().getVar(ram.getAddress(0), RAM_SIZE, false, Reason.INSPECT);
	}

	private void setRam(byte[] state) {
		emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, state.clone());
	}

	private void setStackPointer(long value) {
		thread.getState().setVar(stackPointer,
			arith.fromConst(value, stackPointer.getMinimumByteSize()));
		Register ramWord = currentProgram.getRegister("StackPtr");
		if (ramWord != null && ramWord.getAddress().getAddressSpace().equals(ram)) {
			writeMemory(ramWord.getAddress().getOffset(), 2, value);
		}
	}

	private long dataBankOffset() {
		if (dataBank == null) {
			return 0;
		}
		BigInteger bank = thread.getContext().getRegisterValue(dataBank).getUnsignedValue();
		return bank == null ? 0 : bank.longValue() << 16;
	}

	private long bankedData(long address16) {
		return address16 >= ROM_START ? dataBankOffset() + address16 : address16;
	}

	private void printBaseRegisters() {
		StringBuilder sb = new StringBuilder("words in 0x22-0xff after init:");
		for (long a = 0x22; a < 0x100; a += 2) {
			long value = readMemory(a, 2);
			if (value != 0) {
				sb.append(String.format(" %02x=%04x", a, value));
			}
		}
		println(sb.toString());
	}

	// ---- running code ----

	private void startAt(long address) {
		Address a = ram.getAddress(address);
		thread.overrideCounter(a);
		thread.overrideContext(contextAt(a));
	}

	private RegisterValue contextAt(Address a) {
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		return ins == null ? context : context.combineValues(ins.getRegisterValue(base));
	}

	private int runUntil(long stop, int maxSteps) {
		for (int steps = 0; steps < maxSteps; steps++) {
			if (thread.getCounter().getOffset() == stop) {
				return steps;
			}
			thread.stepInstruction();
		}
		throw new IllegalStateException(String.format("did not reach %05x in %d instructions; " +
			"stopped at %s", stop, maxSteps, thread.getCounter()));
	}

	private long functionOf(long address) {
		Function f = getFunctionContaining(ram.getAddress(address));
		return f == null ? -1 : f.getEntryPoint().getOffset();
	}

	private String hex(long v) {
		return v < 0 ? "-" : String.format("%05x", v);
	}

	private void addRow(String kind, long at, long value, long value2, String detail) {
		rows.add(String.format("%s\t%s\t%s\t%s\t%s\t%s", kind, hex(at), hex(functionOf(at)),
			hex(value), value2 < 0 ? "-" : String.format("%x", value2), detail));
	}

	// ---- listing ----

	private void writeListing(Path out) throws Exception {
		Files.createDirectories(out.toAbsolutePath().getParent());
		SimpleBlockModel model = new SimpleBlockModel(currentProgram);
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
			w.println("addr\tfunction\tblock\tbytes\tmnemonic\toperands\tflows\tfallthrough");
			for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
				StringBuilder bytes = new StringBuilder();
				for (byte b : ins.getBytes()) {
					bytes.append(String.format("%02x", b & 0xff));
				}
				StringBuilder flows = new StringBuilder();
				for (Address f : ins.getFlows()) {
					flows.append(flows.length() == 0 ? "" : ",").append(hex(f.getOffset()));
				}
				CodeBlock block = model.getFirstCodeBlockContaining(ins.getAddress(), monitor);
				String text = ins.toString();
				String operands = text.substring(ins.getMnemonicString().length()).trim();
				Address fall = ins.getFallThrough();
				w.println(String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s",
					hex(ins.getAddress().getOffset()), hex(functionOf(ins.getAddress().getOffset())),
					block == null ? "-" : hex(block.getMinAddress().getOffset()), bytes,
					ins.getMnemonicString(), operands, flows, fall == null ? "-" : hex(fall.getOffset())));
			}
		}
	}

	// ---- basic blocks ----

	private record Access(String kind, long address, long value, int size) {
	}

	private void classifyBlocks() throws Exception {
		Map<Long, List<Access>> first = emulateBlocks(ramAfterInit);
		Map<Long, List<Access>> second = emulateBlocks(ramFilled);
		for (Map.Entry<Long, List<Access>> e : first.entrySet()) {
			List<Access> other = second.getOrDefault(e.getKey(), List.of());
			for (int i = 0; i < e.getValue().size() && i < other.size(); i++) {
				Access a = e.getValue().get(i);
				Access b = other.get(i);
				if (!a.kind().equals(b.kind())) {
					continue;
				}
				if (a.kind().equals("value")) {
					boolean toStack = a.address() > STACK_TOP - 0x100 && a.address() <= STACK_TOP;
					if (!toStack && a.address() == b.address() && a.value() == b.value() &&
						inRange(a.value())) {
						addRow("value", e.getKey(), a.value(), a.address(), "");
					}
				}
				else if (a.address() == b.address()) {
					if (inRange(a.address())) {
						addRow("load", e.getKey(), a.address(), a.size(), "");
					}
				}
				else if (inRange(a.address()) || inRange(b.address())) {
					addRow("index", e.getKey(), a.address(), b.address(), "");
				}
			}
		}
		println("blocks classified; rows so far " + rows.size());
	}

	private Map<Long, List<Access>> emulateBlocks(byte[] startRam) throws Exception {
		Map<Long, List<Access>> accesses = new HashMap<>();
		recorder.listener = (at, kind, address, value, size) -> {
			if (at >= 0 && !kind.equals("byte")) {
				long v = kind.equals("value") ? bankedData(value) : value;
				accesses.computeIfAbsent(at, k -> new ArrayList<>())
						.add(new Access(kind, address, v, size));
			}
		};
		CodeBlockIterator it = new SimpleBlockModel(currentProgram).getCodeBlocks(monitor);
		while (it.hasNext()) {
			CodeBlock block = it.next();
			Address min = block.getMinAddress();
			if (getInstructionAt(min) == null) {
				continue;
			}
			recorder.current = -1;
			setRam(startRam);
			setStackPointer(STACK_TOP);
			startAt(min.getOffset());
			try {
				for (int i = 0; i < BLOCK_STEPS && block.contains(thread.getCounter()); i++) {
					thread.stepInstruction();
				}
			}
			catch (RuntimeException e) {
				failures.merge("block: " + reason(e), 1, Integer::sum);
				thread.dropInstruction();
			}
		}
		recorder.listener = null;
		return accesses;
	}

	private static String reason(RuntimeException e) {
		String text = String.valueOf(e.getMessage()).replaceAll(" in the library \\S+", "");
		text = text.replaceAll("0x[0-9a-fA-F]+|\\b[0-9a-f]{5,}\\b", "#");
		return text.length() > 60 ? text.substring(0, 60) : text;
	}

	// ---- paths ----

	/** A call on the path: the call instruction and the address that its return goes to. */
	private record Call(long at, long returnTo) {
	}

	private void runPaths() throws Exception {
		int steps = Integer.parseInt(args.getOrDefault("steps", "3000"));
		List<Long> starts = new ArrayList<>();
		CodeBlockIterator it = new SimpleBlockModel(currentProgram).getCodeBlocks(monitor);
		while (it.hasNext()) {
			Address min = it.next().getMinAddress();
			if (getInstructionAt(min) != null && !insideLookup(min.getOffset())) {
				starts.add(min.getOffset());
			}
		}
		int done = 0;
		for (long start : starts) {
			runPath(start, ramAfterInit, "A", steps);
			runPath(start, ramFilled, "B", steps);
			if (++done % 2000 == 0) {
				println(String.format("paths: %d of %d starts", done, starts.size()));
			}
			monitor.checkCancelled();
		}
	}

	private boolean insideLookup(long pc) {
		for (long entry : lookups.keySet()) {
			if (pc >= entry && pc < entry + LOOKUP_SPAN) {
				return true;
			}
		}
		return false;
	}

	private void runPath(long start, byte[] startRam, String run, int maxSteps) {
		pathRuns++;
		setRam(startRam);
		setStackPointer(STACK_TOP);
		startAt(start);
		Deque<Call> calls = new ArrayDeque<>();
		Map<Long, Long> lastWriter = new HashMap<>();
		// loads below each call: key "call/instruction" -> {low, high, count}
		Map<String, long[]> loads = new LinkedHashMap<>();
		recorder.listener = (at, kind, address, value, size) -> {
			if (kind.equals("value") || kind.equals("byte")) {
				for (long reg : watched) {
					if (address <= reg + 1 && reg < address + size) {
						lastWriter.put(reg, at);
					}
				}
			}
			else if (kind.equals("load") && inRange(address) && !calls.isEmpty()) {
				String key = calls.peekLast().at() + "/" + at;
				long[] r = loads.computeIfAbsent(key, k -> new long[] { address, address, 0 });
				r[0] = Math.min(r[0], address);
				r[1] = Math.max(r[1], address + size - 1);
				r[2]++;
			}
		};
		long prevPc = -1;
		try {
			for (int i = 0; i < maxSteps; i++) {
				long pc = thread.getCounter().getOffset();
				Long reg = lookups.get(pc);
				if (reg != null && !(prevPc >= pc && prevPc < pc + LOOKUP_SPAN)) {
					recordLookup(pc, reg, calls, lastWriter, run, start);
				}
				Instruction ins = getInstructionAt(thread.getCounter());
				if (ins == null) {
					failures.merge("path: no instruction", 1, Integer::sum);
					break;
				}
				FlowType flow = ins.getFlowType();
				boolean isCall = flow.isCall();
				boolean isReturn = flow.isTerminal();
				Address fall = ins.getFallThrough();
				recorder.current = pc;
				thread.stepInstruction();
				pathSteps++;
				prevPc = pc;
				if (isCall && fall != null) {
					calls.push(new Call(pc, fall.getOffset()));
				}
				else if (isReturn) {
					if (calls.isEmpty()) {
						break;
					}
					startAt(calls.pop().returnTo());
				}
			}
		}
		catch (RuntimeException e) {
			failures.merge("path: " + reason(e), 1, Integer::sum);
			thread.dropInstruction();
		}
		recorder.listener = null;
		for (Map.Entry<String, long[]> e : loads.entrySet()) {
			String[] parts = e.getKey().split("/");
			long[] r = e.getValue();
			addRow("pload", Long.parseLong(parts[0]), r[0], r[1],
				String.format("run %s; loaded at %s; count %d", run, hex(Long.parseLong(parts[1])),
					r[2]));
		}
	}

	private void recordLookup(long entry, long reg, Deque<Call> calls, Map<Long, Long> lastWriter,
			String run, long start) {
		long table = bankedData(readMemory(reg, 2));
		if (!inRange(table)) {
			return;
		}
		StringBuilder chain = new StringBuilder();
		Iterator<Call> it = calls.descendingIterator();
		while (it.hasNext()) {
			chain.append(chain.length() == 0 ? "" : ">").append(hex(it.next().at()));
		}
		long at = calls.isEmpty() ? -1 : calls.peek().at();
		Long former = lastWriter.get(reg);
		StringBuilder regs = new StringBuilder();
		for (long r : tracked) {
			Long writer = lastWriter.get(r);
			regs.append(String.format("; r%02x=%04x@%s", r, readMemory(r, 2),
				writer == null ? "-" : hex(writer)));
		}
		addRow("lookup", at, table, entry, String.format("run %s; former %s; chain %s; start %s%s",
			run, former == null ? "-" : hex(former), chain, hex(start), regs));
	}

	// ---- emulator callbacks ----

	private interface AccessListener {
		void access(long instruction, String kind, long address, long value, int size);
	}

	private class Recorder implements PcodeEmulationCallbacks<byte[]> {
		long current = -1;
		AccessListener listener;

		@Override
		public void beforeExecuteInstruction(PcodeThread<byte[]> t, Instruction instruction,
				PcodeProgram program) {
			current = instruction.getAddress().getOffset();
		}

		@Override
		public <A, U> void dataWritten(PcodeThread<byte[]> t, PcodeExecutorStatePiece<A, U> piece,
				Address address, int length, U value) {
			if (listener != null && address.getAddressSpace().equals(ram)) {
				if (length == 2) {
					listener.access(current, "value", address.getOffset(),
						piece.getArithmetic().toLong(value, Purpose.INSPECT), 2);
				}
				else if (length == 1) {
					listener.access(current, "byte", address.getOffset(),
						piece.getArithmetic().toLong(value, Purpose.INSPECT), 1);
				}
			}
		}

		@Override
		public void afterLoad(PcodeThread<byte[]> t, PcodeOp op, AddressSpace space, byte[] offset,
				int size, byte[] value) {
			if (listener != null && space.equals(ram)) {
				listener.access(current, "load", arith.toLong(offset, Purpose.LOAD),
					arith.toLong(value, Purpose.INSPECT), size);
			}
		}
	}
}
