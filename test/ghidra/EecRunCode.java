// Runs code of an EEC-IV program in Ghidra's p-code emulator from RAM values that the caller
// chooses. It reports the loads from an address range (for example the calibration bank) and
// RAM values after each run. Use it to see which calibration bytes a routine reads for given
// inputs, and what the routine writes.
//
// The script takes key=value arguments. Addresses and values are hex. Addresses are banked
// (bank * 0x10000 + 16-bit address) when the language has banks.
//
//   init=<a>,<b>        Subroutines to call first, in this order, each until it returns, for
//                       example the routines that load the base registers.
//   poke=<a>:<v>,...    Bytes to change in the emulator's copy of the program before init.
//                       The program is not changed.
//   ram=<a>:<v>,...     RAM bytes to set after init.
//   ramw=<a>:<v>,...    RAM words to set after init.
//   call=<entry>        Call entry and run until it returns.
//   run=<start>-<end>   Run from start until the program counter reaches end. Several runs are
//                       separated by '/'. Each run continues from the RAM that the previous run
//                       left. With "<start>-<end>@<a>:<v>;<a>:<v>:w" the run first sets RAM
//                       bytes, or words with ":w".
//   loads=<lo>-<hi>     Report each load from an address from lo to hi: the instruction, the
//                       address, the size and the value, with a count.
//   show=<a>[:w],...    Print these RAM bytes, or words with ":w", after each run.
//   path=true           Also print the address of each instruction of a run (the first 4000).
//   max=<n>             Instruction limit of a run (decimal, default 200000).
//   cases=<a>:<v>[:w],.../...
//                       With call=: call entry once for each case. Each case starts from the
//                       RAM after init, ram= and ramw=, and sets its own bytes or words.
//   vary=<a>:<lo>-<hi>[:<step>]
//                       With call=: for each case, call entry once for each value of the RAM
//                       byte a. The script prints one line for each case with the show= values
//                       of each run, and does not list loads.
//
// Limits: the same as in test/ghidra/EecEmulatorCheck.java. The p-code stack pointer is not the
// RAM word StackPtr (0x20), R0 is ordinary RAM in the p-code, hardware registers hold what the
// code or the arguments wrote to them, and interrupts do not run.
//@category EEC

import java.math.BigInteger;
import java.util.ArrayList;
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
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.PcodeProgram;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;

public class EecRunCode extends GhidraScript {

	private static final int RAM_SIZE = 0x2000;         // registers and RAM, not banked
	private static final long STACK_TOP = 0x1f00;
	private static final long RETURN_MARK = 0x1ffe;     // return address that ends a call
	private static final int PATH_LIMIT = 4000;

	private Map<String, String> args;
	private PcodeEmulator emu;
	private PcodeThread<byte[]> thread;
	private PcodeArithmetic<byte[]> arith;
	private AddressSpace ram;
	private Register stackPointer;
	private long loadLow = -1;
	private long loadHigh = -1;
	private long current = -1;
	private boolean recordLoads;
	private final LinkedHashMap<String, Integer> loads = new LinkedHashMap<>();

	/** Records the instruction that runs and each load from the loads= range. */
	private class Callbacks implements PcodeEmulationCallbacks<byte[]> {
		@Override
		public void beforeExecuteInstruction(PcodeThread<byte[]> t, Instruction instruction,
				PcodeProgram program) {
			current = instruction.getAddress().getOffset();
		}

		@Override
		public void afterLoad(PcodeThread<byte[]> t, PcodeOp op, AddressSpace space,
				byte[] offset, int size, byte[] value) {
			if (!recordLoads || !space.equals(ram)) {
				return;
			}
			long address = arith.toLong(offset, Purpose.LOAD);
			if (address >= loadLow && address <= loadHigh) {
				long v = arith.toLong(value, Purpose.INSPECT);
				String key = String.format("%05x load %05x size %d value " +
					(size == 1 ? "%02x" : "%04x"), current, address, size, v);
				loads.merge(key, 1, Integer::sum);
			}
		}
	}

	@Override
	protected void run() throws Exception {
		args = parseArgs(getScriptArgs());
		if (!args.containsKey("call") && !args.containsKey("run")) {
			throw new IllegalArgumentException("give call=<entry> or run=<start>-<end>");
		}
		setUpEmulator();
		writeItems(args.getOrDefault("poke", ""), ",", 1);
		for (String entry : list("init")) {
			int steps = call(Long.parseLong(entry, 16), maxSteps());
			println(String.format("init %s: returned after %d instructions", entry, steps));
		}
		writeItems(args.getOrDefault("ram", ""), ",", 1);
		writeItems(args.getOrDefault("ramw", ""), ",", 2);
		if (args.containsKey("loads")) {
			String[] range = args.get("loads").split("-");
			loadLow = Long.parseLong(range[0], 16);
			loadHigh = Long.parseLong(range[1], 16);
		}
		if (args.containsKey("call") && (args.containsKey("cases") || args.containsKey("vary"))) {
			sweep(Long.parseLong(args.get("call"), 16));
			return;
		}
		List<String> runs = new ArrayList<>();
		if (args.containsKey("call")) {
			runs.add("call:" + args.get("call"));
		}
		for (String r : args.getOrDefault("run", "").split("/")) {
			if (!r.isBlank()) {
				runs.add(r.trim());
			}
		}
		for (String r : runs) {
			runOnce(r);
		}
	}

	// ---- arguments ----

	private static Map<String, String> parseArgs(String[] raw) {
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

	private List<String> list(String key) {
		List<String> items = new ArrayList<>();
		for (String item : args.getOrDefault(key, "").split(",")) {
			if (!item.isBlank()) {
				items.add(item.trim());
			}
		}
		return items;
	}

	private int maxSteps() {
		return Integer.parseInt(args.getOrDefault("max", "200000"));
	}

	private boolean pathWanted() {
		String value = args.getOrDefault("path", "");
		return value.equals("true") || value.equals("1");
	}

	/**
	 * Write items "a:v" (the given size) or "a:v:w" (a word) separated by the separator. An
	 * item "a:v:b" writes a byte whatever the default size.
	 */
	private void writeItems(String text, String separator, int defaultSize) {
		for (String item : text.split(separator)) {
			if (item.isBlank()) {
				continue;
			}
			String[] parts = item.trim().split(":");
			if (parts.length < 2) {
				throw new IllegalArgumentException("not address:value: " + item);
			}
			int size = defaultSize;
			if (parts.length > 2) {
				size = parts[2].equals("w") ? 2 : 1;
			}
			write(Long.parseLong(parts[0], 16), size, Long.parseLong(parts[1], 16));
		}
	}

	// ---- emulator state ----

	private void setUpEmulator() throws Exception {
		emu = new PcodeEmulator(currentProgram.getLanguage(), new Callbacks());
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

	private void write(long address, int size, long value) {
		emu.getSharedState().setVar(ram.getAddress(address), size, false,
			arith.fromConst(value, size));
	}

	private long read(long address, int size) {
		byte[] bytes =
			emu.getSharedState().getVar(ram.getAddress(address), size, false, Reason.INSPECT);
		return arith.toLong(bytes, Purpose.INSPECT);
	}

	private void setStackPointer(long value) {
		thread.getState().setVar(stackPointer,
			arith.fromConst(value, stackPointer.getMinimumByteSize()));
	}

	/** Start at an address with the complete context that the program has there. */
	private void startAt(long address) {
		Address a = ram.getAddress(address);
		thread.overrideCounter(a);
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		thread.overrideContext(
			ins == null ? context : context.combineValues(ins.getRegisterValue(base)));
	}

	// ---- running code ----

	/** Push a return address, start at entry and run until the return. */
	private int call(long entry, int max) {
		setStackPointer(STACK_TOP - 2);
		write(STACK_TOP - 2, 2, RETURN_MARK);
		startAt(entry);
		return runUntil((entry & ~0xffffL) | RETURN_MARK, max, null);
	}

	private int runUntil(long stop, int max, StringBuilder path) {
		for (int steps = 0; steps < max; steps++) {
			long pc = thread.getCounter().getOffset();
			if (path != null && steps < PATH_LIMIT) {
				path.append(String.format(" %05x", pc));
			}
			if (pc == stop) {
				return steps;
			}
			thread.stepInstruction();
		}
		throw new IllegalStateException(String.format(
			"did not reach %05x in %d instructions; stopped at %s", stop, max,
			thread.getCounter()));
	}

	/** One item of call= or run=, with the RAM changes after '@'. */
	private void runOnce(String item) {
		String r = item;
		int at = r.indexOf('@');
		if (at >= 0) {
			writeItems(r.substring(at + 1), ";", 1);
			r = r.substring(0, at);
		}
		loads.clear();
		recordLoads = true;
		StringBuilder path = pathWanted() ? new StringBuilder() : null;
		int steps;
		if (r.startsWith("call:")) {
			steps = call(Long.parseLong(r.substring(5), 16), maxSteps());
		}
		else {
			String[] ends = r.split("-");
			setStackPointer(STACK_TOP);
			startAt(Long.parseLong(ends[0], 16));
			steps = runUntil(Long.parseLong(ends[1], 16), maxSteps(), path);
		}
		recordLoads = false;
		println(String.format("run %s: %d instructions", r, steps));
		for (Map.Entry<String, Integer> e : loads.entrySet()) {
			println("  " + e.getKey() + (e.getValue() > 1 ? " (x" + e.getValue() + ")" : ""));
		}
		if (path != null) {
			println("  path:" + path);
		}
		println("  show:" + shownValues(" ", "="));
	}

	/** The show= values, each as address=value (or only the values with an empty equals). */
	private String shownValues(String separator, String equals) {
		StringBuilder sb = new StringBuilder();
		for (String item : list("show")) {
			String[] parts = item.split(":");
			int size = parts.length > 1 && parts[1].equals("w") ? 2 : 1;
			long address = Long.parseLong(parts[0], 16);
			sb.append(separator);
			if (!equals.isEmpty()) {
				sb.append(String.format("%x", address)).append(equals);
			}
			sb.append(String.format(size == 1 ? "%02x" : "%04x", read(address, size)));
		}
		return sb.toString();
	}

	/** Call entry for each case, and for each value of the vary= byte. */
	private void sweep(long entry) {
		byte[] base = emu.getSharedState().getVar(ram.getAddress(0), RAM_SIZE, false,
			Reason.INSPECT);
		List<String> cases = new ArrayList<>(List.of(args.getOrDefault("cases", "").split("/")));
		long varied = -1;
		long low = 0;
		long high = 0;
		long step = 1;
		if (args.containsKey("vary")) {
			String[] parts = args.get("vary").split(":");
			varied = Long.parseLong(parts[0], 16);
			String[] range = parts[1].split("-");
			low = Long.parseLong(range[0], 16);
			high = Long.parseLong(range[1], 16);
			if (parts.length > 2) {
				step = Long.parseLong(parts[2], 16);
			}
		}
		for (String c : cases) {
			StringBuilder line = new StringBuilder("case " + c + ":");
			for (long v = low; v <= high; v += step) {
				emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, base.clone());
				writeItems(c, ",", 1);
				if (varied >= 0) {
					write(varied, 1, v);
					line.append(String.format(" %02x=", v));
				}
				else {
					line.append(' ');
				}
				call(entry, maxSteps());
				String values = shownValues("/", "");
				line.append(values.isEmpty() ? "" : values.substring(1));
			}
			println(line.toString());
		}
	}
}
