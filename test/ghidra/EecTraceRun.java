// Runs ranges of code of an EEC-IV program in Ghidra's p-code emulator, with RAM values that
// the caller chooses, and reports what the code read and computed:
//   * each load from and each store to chosen address ranges (for example a calibration table
//     or an output register), with the instruction that made it;
//   * each entry into chosen subroutines and the return from it, with a set of registers;
//   * chosen RAM values at the end of the run.
// Use it to show which bytes a routine reads and what it does with them. The emulator setup is
// the same as in EecEmulatorCheck.java.
//
// Run it on an analyzed project (test/run-emulator-check.sh shows how to make one), without
// changing the program:
//   analyzeHeadless <project folder> <project> -process <image> -noanalysis -readOnly
//     -scriptPath "ghidra_scripts;test/ghidra" -postScript EecTraceRun.java
//     cases=<file> out=<file> init=<routines> [watch=...] [probe=...] [show=...]
//
// Arguments (key=value). Addresses are banked hex: bank * 0x10000 + 16-bit address.
//   out=<path>          result rows, tab-separated (required)
//   cases=<path>        the runs, one per line (required). A line has three tab-separated
//                       fields:  name   start-end   settings
//                       The run starts at start and stops when the program counter reaches
//                       end. settings is a list of items separated by spaces:
//                         b:<a>=<v>   a RAM byte to set before the run (hex)
//                         w:<a>=<v>   a RAM word to set before the run (hex)
//                         p:<a>=<v>   a program byte to change for this run only (hex)
//                       Empty lines and lines that start with # are ignored.
//   init=<a>,<b>        subroutines to run once before the cases, in this order, each until it
//                       returns (for example the routines that load the base registers). Each
//                       case starts from the RAM after init.
//   watch=<lo>-<hi>,... report each load from these address ranges (hi is not included)
//   stores=<lo>-<hi>,... report each store to these address ranges (hi is not included)
//   probe=<a>,...       report each entry into these subroutines and the return from it
//   regs=<r>,...        the words (hex register addresses) that probe rows show
//                       (default 34,36,38,3a,3c,3e,60)
//   show=<a>:<n>,...    report n bytes of RAM at a (hex, n 1 or 2) at the end of each run
//   steps=<n>           the most instructions in one run (default 200000)
//   path=<lo>-<hi>,...  report each instruction that runs in these address ranges
//
// Result rows (header: case kind pc address size value detail), values hex:
//   load    pc = the instruction, address = the banked address loaded, size and value
//   store   pc = the instruction, address = the address written, size and value
//   enter   pc = the subroutine entry, detail = the registers; address = the return address
//   return  pc = the return address, detail = the registers
//   show    address, size and value of RAM at the end
//   step    pc = an instruction in a path= range that runs
//   end     pc = where the run stopped, value = the number of instructions; detail is "did not
//           reach end" if the run stopped before end (steps= reached, or an emulator error)
//
// A probe entry that the code reaches again with the same stack pointer, before the return,
// is part of the same call (a routine that jumps back to its entry, or an entry that runs on
// into another probed entry).
//
// Limits of the emulation (as in EecEmulatorCheck.java): the p-code stack pointer is not the
// RAM word StackPtr (0x20); R0 is ordinary RAM; hardware registers (0x02-0x1f) read 0, so code
// that waits for a hardware bit does not finish.
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;

public class EecTraceRun extends GhidraScript {

	private static final String HEADER = "case\tkind\tpc\taddress\tsize\tvalue\tdetail\n";
	private static final int RAM_SIZE = 0x2000;         // registers and RAM, not banked
	private static final long STACK_TOP = 0x1f00;
	private static final long RETURN_MARK = 0x1ffe;     // return address that ends an init call
	private static final int INIT_STEPS = 100000;

	private Map<String, String> args;
	private PcodeEmulator emu;
	private PcodeThread<byte[]> thread;
	private PcodeArithmetic<byte[]> arith;
	private AddressSpace ram;
	private Register stackPointer;
	private byte[] ramAfterInit;
	private final List<long[]> watch = new ArrayList<>();
	private final List<long[]> stores = new ArrayList<>();
	private final List<long[]> path = new ArrayList<>();
	private final List<Long> probes = new ArrayList<>();
	private final List<Long> regs = new ArrayList<>();
	private final List<long[]> show = new ArrayList<>();
	private final Recorder recorder = new Recorder();

	/** One run: a name, a start and an end address, and the settings. */
	private record Case(String name, long start, long end, List<String> settings) {
	}

	/** A probed call whose return has not been reached yet. */
	private record OpenCall(long entry, long returnTo, long sp) {
	}

	@Override
	protected void run() throws Exception {
		args = parseArgs(getScriptArgs());
		readOptions();
		List<Case> cases = readCases(Path.of(require("cases")));
		setUpEmulator();
		for (long entry : addresses("init")) {
			setStackPointer(STACK_TOP - 2);
			writeMemory(STACK_TOP - 2, 2, RETURN_MARK);
			startAt(entry);
			int steps = runUntil((entry & ~0xffffL) | RETURN_MARK, null, INIT_STEPS);
			println(String.format("init %05x: returned after %d instructions", entry, steps));
		}
		ramAfterInit = readMemoryBytes(0, RAM_SIZE);
		Path out = Path.of(require("out"));
		Files.createDirectories(out.toAbsolutePath().getParent());
		int maxSteps = Integer.parseInt(args.getOrDefault("steps", "200000"));
		try (PrintWriter w =
			new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
			w.print(HEADER);
			for (Case c : cases) {
				runCase(w, c, maxSteps);
			}
		}
		println(String.format("ran %d cases; rows in %s", cases.size(), out));
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

	private List<String> list(String key, String defaultValue) {
		List<String> items = new ArrayList<>();
		for (String item : args.getOrDefault(key, defaultValue).split(",")) {
			if (!item.isBlank()) {
				items.add(item.trim());
			}
		}
		return items;
	}

	private List<Long> addresses(String key) {
		List<Long> values = new ArrayList<>();
		for (String item : list(key, "")) {
			values.add(Long.parseLong(item, 16));
		}
		return values;
	}

	private void readOptions() {
		watch.addAll(ranges("watch"));
		stores.addAll(ranges("stores"));
		path.addAll(ranges("path"));
		probes.addAll(addresses("probe"));
		for (String item : list("regs", "34,36,38,3a,3c,3e,60")) {
			regs.add(Long.parseLong(item, 16));
		}
		for (String item : list("show", "")) {
			String[] parts = item.split(":");
			show.add(new long[] { Long.parseLong(parts[0], 16), Long.parseLong(parts[1]) });
		}
	}

	/** Address ranges given as lo-hi,... (hex, hi not included). */
	private List<long[]> ranges(String key) {
		List<long[]> result = new ArrayList<>();
		for (String item : list(key, "")) {
			String[] ends = item.split("-");
			result.add(new long[] { Long.parseLong(ends[0], 16), Long.parseLong(ends[1], 16) });
		}
		return result;
	}

	private static boolean inRanges(List<long[]> ranges, long address) {
		for (long[] range : ranges) {
			if (address >= range[0] && address < range[1]) {
				return true;
			}
		}
		return false;
	}

	private List<Case> readCases(Path path) throws Exception {
		List<Case> cases = new ArrayList<>();
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split("\t");
			if (fields.length < 2) {
				throw new IllegalArgumentException("case line needs name and start-end: " + line);
			}
			String[] ends = fields[1].trim().split("-");
			List<String> settings = new ArrayList<>();
			if (fields.length > 2) {
				for (String s : fields[2].trim().split("\\s+")) {
					if (!s.isBlank()) {
						settings.add(s);
					}
				}
			}
			cases.add(new Case(fields[0].trim(), Long.parseLong(ends[0], 16),
				Long.parseLong(ends[1], 16), settings));
		}
		return cases;
	}

	// ---- emulator state ----

	private void setUpEmulator() throws Exception {
		emu = new PcodeEmulator(currentProgram.getLanguage(), recorder);
		arith = emu.getArithmetic();
		ram = currentProgram.getAddressFactory().getDefaultAddressSpace();
		stackPointer = currentProgram.getCompilerSpec().getStackPointer();
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

	private byte[] readMemoryBytes(long address, int size) {
		return emu.getSharedState().getVar(ram.getAddress(address), size, false, Reason.INSPECT);
	}

	/** Set the p-code stack pointer, and the RAM word StackPtr if the language names it. */
	private void setStackPointer(long value) {
		thread.getState().setVar(stackPointer,
			arith.fromConst(value, stackPointer.getMinimumByteSize()));
		Register ramWord = currentProgram.getRegister("StackPtr");
		if (ramWord != null && ramWord.getAddress().getAddressSpace().equals(ram)) {
			writeMemory(ramWord.getAddress().getOffset(), 2, value);
		}
	}

	private long stackPointerValue() {
		byte[] value = thread.getState().getVar(stackPointer, Reason.INSPECT);
		return arith.toLong(value, Purpose.INSPECT);
	}

	/** Start at an address with the complete context that the program has there. */
	private void startAt(long address) {
		Address a = ram.getAddress(address);
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		if (ins != null) {
			context = context.combineValues(ins.getRegisterValue(base));
		}
		thread.overrideCounter(a);
		thread.overrideContext(context);
	}

	/** Called before each instruction of a run with the program counter. */
	private interface StepObserver {
		void at(long pc);
	}

	private int runUntil(long stop, StepObserver observer, int maxSteps) {
		for (int steps = 0; steps < maxSteps; steps++) {
			long pc = thread.getCounter().getOffset();
			if (observer != null) {
				observer.at(pc);
			}
			if (pc == stop) {
				return steps;
			}
			thread.stepInstruction();
		}
		return -1;
	}

	// ---- cases ----

	private void runCase(PrintWriter w, Case c, int maxSteps) {
		emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, ramAfterInit.clone());
		Map<Long, Long> restore = new LinkedHashMap<>();
		for (String s : c.settings()) {
			String[] kv = s.substring(2).split("=");
			long a = Long.parseLong(kv[0], 16);
			long v = Long.parseLong(kv[1], 16);
			switch (s.charAt(0)) {
				case 'b' -> writeMemory(a, 1, v);
				case 'w' -> writeMemory(a, 2, v);
				case 'p' -> {
					restore.putIfAbsent(a, readMemory(a, 1));
					writeMemory(a, 1, v);
				}
				default -> throw new IllegalArgumentException("unknown setting " + s);
			}
		}
		setStackPointer(STACK_TOP);
		startAt(c.start());
		Deque<OpenCall> open = new ArrayDeque<>();
		recorder.listener = (pc, kind, address, size, value) -> {
			if (inRanges(kind.equals("load") ? watch : stores, address)) {
				row(w, c, kind, pc, address, size, value, "");
			}
		};
		StepObserver observer = pc -> {
			if (inRanges(path, pc)) {
				row(w, c, "step", pc, 0, 0, 0, "");
			}
			OpenCall top = open.peek();
			if (top != null && pc == top.returnTo() && stackPointerValue() == top.sp() + 2) {
				open.pop();
				row(w, c, "return", pc, top.entry(), 0, 0, registers());
			}
			if (probes.contains(pc)) {
				long sp = stackPointerValue();
				if (top != null && top.sp() == sp) {
					return;   // the same call: a jump back to the entry, or a run-on entry
				}
				long returnTo = (pc & ~0xffffL) | readMemory(sp, 2);
				open.push(new OpenCall(pc, returnTo, sp));
				row(w, c, "enter", pc, returnTo, 0, 0, registers());
			}
		};
		int steps;
		try {
			steps = runUntil(c.end(), observer, maxSteps);
		}
		catch (RuntimeException e) {
			steps = -1;
			println(String.format("case %s: stopped at %s: %s", c.name(), thread.getCounter(),
				e.getMessage()));
			thread.dropInstruction();
		}
		recorder.listener = null;
		for (long[] s : show) {
			row(w, c, "show", 0, s[0], (int) s[1], readMemory(s[0], (int) s[1]), "");
		}
		row(w, c, "end", thread.getCounter().getOffset(), 0, 0, Math.max(steps, 0),
			steps < 0 ? "did not reach end" : "");
		if (steps < 0) {
			println(String.format("case %s: did not reach %05x", c.name(), c.end()));
		}
		for (Map.Entry<Long, Long> e : restore.entrySet()) {
			writeMemory(e.getKey(), 1, e.getValue());
		}
	}

	private String registers() {
		StringBuilder sb = new StringBuilder();
		for (long r : regs) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(String.format("R%02x=%04x", r, readMemory(r, 2)));
		}
		return sb.toString();
	}

	private void row(PrintWriter w, Case c, String kind, long pc, long address, int size,
			long value, String detail) {
		w.print(String.format("%s\t%s\t%05x\t%05x\t%d\t%x\t%s\n", c.name(), kind, pc, address,
			size, value, detail));
	}

	// ---- emulator callbacks ----

	/**
	 * Receives each memory access of an instruction: kind "load" or "store", the address (banked
	 * for a load), the size and the value.
	 */
	private interface AccessListener {
		void access(long instruction, String kind, long address, int size, long value);
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
		public void afterLoad(PcodeThread<byte[]> t, PcodeOp op, AddressSpace space, byte[] offset,
				int size, byte[] value) {
			if (listener != null && space.equals(ram)) {
				listener.access(current, "load", arith.toLong(offset, Purpose.LOAD), size,
					arith.toLong(value, Purpose.INSPECT));
			}
		}

		// The byte state reports writes with a concrete address.
		@Override
		public <A, U> void dataWritten(PcodeThread<byte[]> t, PcodeExecutorStatePiece<A, U> piece,
				Address address, int length, U value) {
			if (listener != null && address.getAddressSpace().equals(ram)) {
				listener.access(current, "store", address.getOffset(), length,
					piece.getArithmetic().toLong(value, Purpose.INSPECT));
			}
		}
	}
}
