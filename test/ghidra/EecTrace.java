// Runs real code of an EEC-IV program in Ghidra's p-code emulator from a start address to an end
// address, with RAM inputs that the caller gives, and records every entry into the routines that
// the caller names: the registers at the entry and at the return. Use it to see which tables a
// routine reads, with which inputs, and what each lookup returns.
//
// Arguments (key=value). Addresses are banked hex: bank * 0x10000 + 16-bit address.
//   out=<path>          result rows, tab-separated (required)
//   init=<a>,<b>        subroutines to run first, each until it returns (for example the
//                       routines that load the base registers)
//   ram=<a>:<v>,...     RAM bytes to set after init (hex)
//   words=<a>:<v>,...   RAM words to set after init (hex; a negative value is allowed, for
//                       example 116:-2a)
//   poke=<a>:<v>,...    bytes to change in the emulator's copy of the program before init
//   run=<s>-<e>,...     code to run, in this order, each from s until it reaches e; the RAM
//                       carries over from one run to the next
//   cases=<path>        a text file with one case per line: words=... and ram=... items as
//                       above, separated by spaces (lines that start with # are skipped).
//                       Each case starts from the RAM after init and the ram= and words=
//                       arguments, then runs the run= list. Without cases= there is one case.
//   watch=<entry>:<r>.<r>.../<r>.<r>...,...
//                       routines to record: at each entry the words in the registers before
//                       the slash, at its return the words in the registers after it (hex
//                       register numbers). Example: 88062:3c.34.36.38/3e
//   dump=<a>,...        RAM words to write after each run
//   max=<n>             most instructions in one run (default 200000)
//
// Rows (header: case run kind at entry values):
//   case   the number of the case (0 without cases=)
//   run    the start of the run
//   kind   entry, return, dump, or failed (the case stopped early; values: the reason)
//   at     the number of instructions run so far (entry, return) or the RAM address (dump)
//   entry  the routine (entry, return)
//   values register=value pairs, hex
//
// Limits: as in EecEmulatorCheck.java. The p-code stack pointer SP is not the RAM word
// StackPtr (0x20); hardware registers read 0; the emulator stops at a divide by 0 and at
// NORML. A routine that the code enters again before it returns (recursion) is recorded once.
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
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeArithmetic;
import ghidra.pcode.exec.PcodeArithmetic.Purpose;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;

public class EecTrace extends GhidraScript {

	private static final int RAM_SIZE = 0x2000;
	private static final long STACK_TOP = 0x1f00;
	private static final long RETURN_MARK = 0x1ffe;
	private static final int INIT_STEPS = 100000;

	private Map<String, String> args;
	private PcodeEmulator emu;
	private PcodeThread<byte[]> thread;
	private PcodeArithmetic<byte[]> arith;
	private AddressSpace ram;
	private Register stackPointer;

	/** A routine to record, with the registers to write at its entry and at its return. */
	private record Watch(long entry, long[] atEntry, long[] atReturn) {
	}

	/** An entry whose return has not been reached yet. */
	private record Open(Watch watch, long returnTo, long sp) {
	}

	@Override
	protected void run() throws Exception {
		args = new HashMap<>();
		for (String arg : getScriptArgs()) {
			int eq = arg.indexOf('=');
			if (eq <= 0) {
				throw new IllegalArgumentException("argument is not key=value: " + arg);
			}
			args.put(arg.substring(0, eq), arg.substring(eq + 1));
		}
		String out = args.get("out");
		if (out == null) {
			throw new IllegalArgumentException("missing argument out=...");
		}
		setUpEmulator();
		forEachPair(args.getOrDefault("poke", ""), (a, v) -> writeMemory(a, 1, v));
		for (String item : list("init")) {
			long entry = Long.parseLong(item, 16);
			println(String.format("init %05x: returned after %d instructions", entry,
				callAt(entry)));
		}
		forEachPair(args.getOrDefault("ram", ""), (a, v) -> writeMemory(a, 1, v));
		forEachPair(args.getOrDefault("words", ""), (a, v) -> writeMemory(a, 2, v));
		byte[] startRam = readMemoryBytes(0, RAM_SIZE);
		List<String> cases = new ArrayList<>();
		if (args.containsKey("cases")) {
			for (String line : Files.readAllLines(Path.of(args.get("cases")))) {
				if (!line.isBlank() && !line.startsWith("#")) {
					cases.add(line.trim());
				}
			}
		}
		else {
			cases.add("");
		}
		Map<Long, Watch> watches = parseWatches();
		int maxSteps = Integer.parseInt(args.getOrDefault("max", "200000"));
		Path path = Path.of(out);
		Files.createDirectories(path.toAbsolutePath().getParent());
		int failed = 0;
		try (PrintWriter w =
			new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
			w.print("case\trun\tkind\tat\tentry\tvalues\n");
			for (int c = 0; c < cases.size(); c++) {
				// Each case starts from the RAM after init and the ram= and words= arguments.
				emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, startRam.clone());
				applyCase(cases.get(c));
				String prefix = c + "\t";
				try {
					for (String item : list("run")) {
						String[] ends = item.split("-");
						long start = Long.parseLong(ends[0], 16);
						long end = Long.parseLong(ends[1], 16);
						runTraced(w, prefix, start, end, watches, maxSteps);
						for (String d : list("dump")) {
							long a = Long.parseLong(d, 16);
							w.print(String.format("%s%05x\tdump\t%04x\t-\t%04x\n", prefix, start,
								a, readMemory(a, 2)));
						}
					}
				}
				catch (RuntimeException e) {
					// For example a divide by 0, or a run that does not reach its end.
					w.print(String.format("%s-\tfailed\t-\t-\t%s\n", prefix,
						String.valueOf(e.getMessage()).replace('\t', ' ').replace('\n', ' ')));
					thread.dropInstruction();
					failed++;
				}
			}
		}
		println(String.format("cases %d, cases that stopped early %d", cases.size(), failed));
	}

	/** Apply the words= and ram= items of one line of the cases file. */
	private void applyCase(String line) {
		for (String part : line.split("\\s+")) {
			if (part.startsWith("words=")) {
				forEachPair(part.substring(6), (a, v) -> writeMemory(a, 2, v));
			}
			else if (part.startsWith("ram=")) {
				forEachPair(part.substring(4), (a, v) -> writeMemory(a, 1, v));
			}
		}
	}

	private int runTraced(PrintWriter w, String prefix, long start, long end,
			Map<Long, Watch> watches, int maxSteps) {
		setStackPointer(STACK_TOP);
		startAt(ram.getAddress(start));
		Deque<Open> open = new ArrayDeque<>();
		for (int steps = 0; steps < maxSteps; steps++) {
			long pc = thread.getCounter().getOffset();
			Open top = open.peek();
			if (top != null && pc == top.returnTo() && stackPointerValue() == top.sp() + 2) {
				open.pop();
				w.print(String.format("%s%05x\treturn\t%d\t%05x\t%s\n", prefix, start, steps,
					top.watch().entry(), registers(top.watch().atReturn())));
			}
			if (pc == end) {
				return steps;
			}
			Watch watch = watches.get(pc);
			if (watch != null && !reentry(open, watch)) {
				long sp = stackPointerValue();
				long returnTo = (pc & ~0xffffL) | readMemory(sp, 2);
				open.push(new Open(watch, returnTo, sp));
				w.print(String.format("%s%05x\tentry\t%d\t%05x\t%s\n", prefix, start, steps,
					pc, registers(watch.atEntry())));
			}
			thread.stepInstruction();
		}
		throw new IllegalStateException(String.format("did not reach %05x in %d instructions; " +
			"stopped at %s", end, maxSteps, thread.getCounter()));
	}

	/** True if the routine is open with the same stack: the code jumps back to its entry. */
	private boolean reentry(Deque<Open> open, Watch watch) {
		for (Open o : open) {
			if (o.watch() == watch && o.sp() == stackPointerValue()) {
				return true;
			}
		}
		return false;
	}

	private String registers(long[] regs) {
		StringBuilder sb = new StringBuilder();
		for (long r : regs) {
			sb.append(String.format("%sR%02x=%04x", sb.length() == 0 ? "" : " ", r,
				readMemory(r, 2)));
		}
		return sb.toString();
	}

	// ---- arguments ----

	private List<String> list(String key) {
		List<String> items = new ArrayList<>();
		for (String item : args.getOrDefault(key, "").split(",")) {
			if (!item.isBlank()) {
				items.add(item.trim());
			}
		}
		return items;
	}

	private interface PairAction {
		void apply(long address, long value);
	}

	private void forEachPair(String text, PairAction action) {
		for (String item : text.split(",")) {
			if (item.isBlank()) {
				continue;
			}
			String[] parts = item.split(":");
			long value = parts[1].startsWith("-") ? -Long.parseLong(parts[1].substring(1), 16)
					: Long.parseLong(parts[1], 16);
			action.apply(Long.parseLong(parts[0], 16), value);
		}
	}

	private Map<Long, Watch> parseWatches() {
		Map<Long, Watch> watches = new LinkedHashMap<>();
		for (String item : list("watch")) {
			String[] parts = item.split(":", 2);
			long entry = Long.parseLong(parts[0], 16);
			String[] sides = (parts.length > 1 ? parts[1] : "").split("/", -1);
			watches.put(entry, new Watch(entry, registerList(sides[0]),
				registerList(sides.length > 1 ? sides[1] : "")));
		}
		return watches;
	}

	private static long[] registerList(String text) {
		if (text.isBlank()) {
			return new long[0];
		}
		String[] parts = text.split("\\.");
		long[] regs = new long[parts.length];
		for (int i = 0; i < parts.length; i++) {
			regs[i] = Long.parseLong(parts[i], 16);
		}
		return regs;
	}

	// ---- emulator state (as in EecEmulatorCheck.java) ----

	private void setUpEmulator() throws Exception {
		emu = new PcodeEmulator(currentProgram.getLanguage());
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

	private byte[] readMemoryBytes(long address, int size) {
		return emu.getSharedState().getVar(ram.getAddress(address), size, false, Reason.INSPECT);
	}

	private long readMemory(long address, int size) {
		byte[] bytes =
			emu.getSharedState().getVar(ram.getAddress(address), size, false, Reason.INSPECT);
		return arith.toLong(bytes, Purpose.INSPECT);
	}

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

	private void startAt(Address a) {
		thread.overrideCounter(a);
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		thread.overrideContext(
			ins == null ? context : context.combineValues(ins.getRegisterValue(base)));
	}

	private int callAt(long entry) {
		setStackPointer(STACK_TOP - 2);
		writeMemory(STACK_TOP - 2, 2, RETURN_MARK);
		startAt(ram.getAddress(entry));
		long stop = (entry & ~0xffffL) | RETURN_MARK;
		for (int steps = 0; steps < INIT_STEPS; steps++) {
			if (thread.getCounter().getOffset() == stop) {
				return steps;
			}
			thread.stepInstruction();
		}
		throw new IllegalStateException(String.format("init %05x did not return", entry));
	}
}
