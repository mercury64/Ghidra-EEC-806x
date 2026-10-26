// Runs the Ford function lookup routines of an EEC-IV program in Ghidra's p-code emulator
// (ghidra.pcode.emu.PcodeEmulator) and writes each input and result, so that the p-code of the
// language can be checked against an independent calculation
// (test/emulator/ford_function.py compare). It can also run real code until it enters a lookup
// routine (a trace), and search the code for references to the tables.
// test/run-emulator-check.sh runs it; docs/emulator-check.md explains the method.
//
// A Ford function is a list of (x, y) pairs, x going down to its lowest value. The lookup
// entries that this script calls take these inputs (regs= changes the register numbers):
//   R36 (word)  the 16-bit address of the function, in the data bank
//   R38         x (byte or word)
//   R60 bit 5   x is signed; R60 bit 4: y is signed (the routine clears both bits)
// and return y in R3c (a word; the byte routine clears R3d).
//
// Arguments (key=value). Addresses are banked hex: bank * 0x10000 + 16-bit address.
//   out=<path>          result rows, tab-separated (required)
//   init=<a>,<b>        subroutines to run first, in this order, each until it returns, for
//                       example the routines that load the base registers
//   ram=<a>:<v>,...     RAM bytes to set after init (hex)
//   poke=<a>:<v>,...    bytes to change in the emulator's copy of the program before init
//                       (hex; the program is not changed). With these the results show which
//                       bytes a lookup reads: a byte that it does not read changes no result.
//   byte=<entry>        entry of the byte lookup with the inputs above
//   word=<entry>        entry of the word lookup with the inputs above
//   descriptor=<entry>  entry of a byte lookup that reads two inline argument bytes after the
//                       call: the address of a 4-byte record (bytes 0-1: the offset of the
//                       function from a base register in bits 0-11 and the base register
//                       0xf0 + n in bits 12-15; bytes 2-3: the address of x). The routine must
//                       continue into byte=. Each byte function is also called this way.
//   tables=<a>[:w][:sx][:sy],...  functions to call (w: word function, sx: signed x,
//                       sy: signed y)
//   inputs=breakpoints|all  x values for each function: every breakpoint, the midpoints, the
//                       lowest and the highest value (default), or also every value (byte
//                       functions; word functions every 0x101)
//   traces=<start>-<end>,...  run from start until end, and record each entry into byte= or
//                       word= with the table, x, the flags and the result
//   regs=36,38,3c,60    the table, x, result and flag registers
//   refs=<path>         also search for references to the tables and write them to this file
//   margin=<n>          with refs: also report addresses up to n bytes before a table
//                       (default 2)
//   span=<n>            with refs: the number of bytes of each table to search for, if more
//                       than the pairs that the routine reads (hex; for example 14 for
//                       10 stored byte pairs)
//
// Result rows (header: mode start routine table width signed_x signed_y x result steps):
//   mode is call (a direct call of byte= or word=), descriptor (a call of descriptor=) or
//   trace; start is the called entry or the start of the trace; values are hex.
//
// Reference rows (header: kind at target table detail), one per reference to an address from
// margin bytes before a table to its last pair (or to the end of span=):
//   ghidra-ref       a reference that Ghidra's analysis made
//   code-value       an instruction that writes the address to a register or RAM. Every basic
//                    block is emulated twice, from the RAM after init with the other bytes 0,
//                    and with the other bytes 2; a value that is the same in both runs depends
//                    only on constants and on registers that init set (for example AD3W with a
//                    base register and an offset)
//   code-load        an instruction that loads from the address, the same in both runs
//   call-site        a block that ends with a call, run through the call until the code
//                    returns after it, in which byte= or word= is entered with this table
//   data-address, data-offset, data-descriptor
//                    a word outside instructions that holds the 16-bit address, its offset from
//                    a register that holds a ROM address after init, or a descriptor record
//                    word for it, and that code uses: a reference from an instruction, a
//                    code-value or code-load of the word's address, an inline argument that
//                    holds it, or a load that depends on an index register and starts up to
//                    0x40 bytes before it (a list)
//
// Limits of the emulation: in the p-code the stack pointer SP is not the RAM word StackPtr
// (0x20), so code that reads or writes 0x20 does not see the stack of PUSH, POP, CALL and RET;
// R0 is ordinary RAM in the p-code, so a write to R0 changes later reads of R0 (the script
// counts such writes); hardware registers (0x02-0x1f) read 0.
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

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
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.symbol.Reference;

public class EecEmulatorCheck extends GhidraScript {

	private static final String HEADER =
		"mode\tstart\troutine\ttable\twidth\tsigned_x\tsigned_y\tx\tresult\tsteps\n";
	private static final int RAM_SIZE = 0x2000;         // registers and RAM, not banked
	private static final int ROM_START = 0x2000;
	private static final long STACK_TOP = 0x1f00;
	private static final long RETURN_MARK = 0x1ffe;     // return address that ends a call
	private static final long SCRATCH_RECORD = 0x1e00;  // descriptor record of a descriptor call
	private static final long SCRATCH_X = 0x1e10;       // x of a descriptor call
	private static final long SCRATCH_ARGS = 0xffe0;    // inline argument bytes, in the code bank
	private static final int MAX_STEPS = 100000;
	private static final int BLOCK_STEPS = 64;
	private static final int CALL_SITE_STEPS = 5000;
	private static final int SIGNED_X_BIT = 5;
	private static final int SIGNED_Y_BIT = 4;
	private static final int MAX_PAIRS = 64;

	private Map<String, String> args;
	private PcodeEmulator emu;
	private PcodeThread<byte[]> thread;
	private PcodeArithmetic<byte[]> arith;
	private AddressSpace ram;
	private Register stackPointer;
	private Register dataBank;
	private final Recorder recorder = new Recorder();
	private byte[] ramAfterInit;
	private long tableReg = 0x36;
	private long xReg = 0x38;
	private long resultReg = 0x3c;
	private long flagsReg = 0x60;
	private long byteEntry = -1;
	private long wordEntry = -1;
	private long descriptorEntry = -1;
	private int r0Writes;
	private int descriptorDecodeErrors;
	private final Map<String, Integer> blockFailures = new TreeMap<>();

	/** Banked word values that code forms from constants and init values: value -> where. */
	private final TreeMap<Long, List<Long>> codeValues = new TreeMap<>();
	/** Addresses that code loads from, that do not depend on other registers: address -> where. */
	private final TreeMap<Long, List<Long>> codeLoads = new TreeMap<>();
	/** Base addresses of loads that depend on another register (an index): base -> where. */
	private final TreeMap<Long, List<Long>> listLoads = new TreeMap<>();
	/** Inline argument words after calls, as banked data addresses: value -> where. */
	private final TreeMap<Long, List<Long>> inlineWords = new TreeMap<>();

	/** A function to call: its banked address, the width of x and y, and the sign flags. */
	private record FunctionSpec(long table, int width, boolean signedX, boolean signedY) {
	}

	/** An address range to search for: a function and the bytes just before it. */
	private record Target(long table, long start, long end) {
	}

	@Override
	protected void run() throws Exception {
		args = parseArgs(getScriptArgs());
		readEntriesAndRegisters();
		setUpEmulator();
		writeBytes(args.getOrDefault("poke", ""));
		for (long entry : addresses("init")) {
			int steps = callAt(entry, RETURN_MARK, null);
			println(String.format("init %05x: returned after %d instructions", entry, steps));
		}
		writeBytes(args.getOrDefault("ram", ""));
		ramAfterInit = readRam(0, RAM_SIZE);
		printRegistersSetByInit();
		List<FunctionSpec> tables = parseTables(args.getOrDefault("tables", ""));
		writeResults(Path.of(require("out")), tables);
		if (args.containsKey("refs")) {
			findReferences(Path.of(args.get("refs")), tables);
		}
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

	private List<Long> addresses(String key) {
		List<Long> values = new ArrayList<>();
		for (String item : list(key)) {
			values.add(Long.parseLong(item, 16));
		}
		return values;
	}

	private long optionalAddress(String key) {
		return args.containsKey(key) ? Long.parseLong(args.get(key), 16) : -1;
	}

	private void readEntriesAndRegisters() {
		byteEntry = optionalAddress("byte");
		wordEntry = optionalAddress("word");
		descriptorEntry = optionalAddress("descriptor");
		List<String> regs = list("regs");
		if (!regs.isEmpty()) {
			if (regs.size() != 4) {
				throw new IllegalArgumentException("regs= needs four registers: table,x,result,flags");
			}
			tableReg = Long.parseLong(regs.get(0), 16);
			xReg = Long.parseLong(regs.get(1), 16);
			resultReg = Long.parseLong(regs.get(2), 16);
			flagsReg = Long.parseLong(regs.get(3), 16);
		}
	}

	private List<FunctionSpec> parseTables(String text) {
		List<FunctionSpec> tables = new ArrayList<>();
		for (String item : text.split(",")) {
			if (item.isBlank()) {
				continue;
			}
			String[] parts = item.trim().split(":");
			List<String> flags = List.of(parts).subList(1, parts.length);
			tables.add(new FunctionSpec(Long.parseLong(parts[0], 16), flags.contains("w") ? 2 : 1,
				flags.contains("sx"), flags.contains("sy")));
		}
		return tables;
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

	private byte[] readRam(long address, int size) {
		return emu.getSharedState().getVar(ram.getAddress(address), size, false, Reason.INSPECT);
	}

	private void restoreRam() {
		emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, ramAfterInit.clone());
	}

	/** Write bytes given as a:v,... (hex) to the emulator's memory. */
	private void writeBytes(String text) {
		for (String item : text.split(",")) {
			if (!item.isBlank()) {
				String[] parts = item.trim().split(":");
				writeMemory(Long.parseLong(parts[0], 16), 1, Long.parseLong(parts[1], 16));
			}
		}
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

	private void writeFlags(boolean signedX, boolean signedY) {
		long flags = readMemory(flagsReg, 1) & ~((1L << SIGNED_X_BIT) | (1L << SIGNED_Y_BIT));
		flags |= (signedX ? 1L << SIGNED_X_BIT : 0) | (signedY ? 1L << SIGNED_Y_BIT : 0);
		writeMemory(flagsReg, 1, flags);
	}

	private boolean flagSet(int bit) {
		return ((readMemory(flagsReg, 1) >> bit) & 1) == 1;
	}

	/** The data bank of the current context (bank * 0x10000), or 0 without banks. */
	private long dataBankOffset() {
		if (dataBank == null) {
			return 0;
		}
		BigInteger bank = thread.getContext().getRegisterValue(dataBank).getUnsignedValue();
		return bank == null ? 0 : bank.longValue() << 16;
	}

	/** A 16-bit data address as a banked address: in the data bank from ROM_START. */
	private long bankedData(long address16) {
		return address16 >= ROM_START ? dataBankOffset() + address16 : address16;
	}

	private void printRegistersSetByInit() {
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

	/** Called before each instruction of a run with the program counter. */
	private interface StepObserver {
		void at(long pc, int steps);
	}

	/** Start at an address with the context that the program has there. */
	private void startAt(long address) {
		Address a = ram.getAddress(address);
		thread.overrideCounter(a);
		thread.overrideContext(contextAt(a));
	}

	/**
	 * The complete context at an address: 0, then the defaults of the language, then the values
	 * that the program stores for the instruction there (for example the register bank after
	 * REGBK). Every bit is set, so that nothing is left from an earlier run: an override with
	 * a partial value keeps the other bits of the thread's context.
	 */
	private RegisterValue contextAt(Address a) {
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		return ins == null ? context : context.combineValues(ins.getRegisterValue(base));
	}

	/** Push a 16-bit return address, start at entry and run until the return. */
	private int callAt(long entry, long return16, StepObserver observer) {
		return call(entry, return16, return16, observer);
	}

	/**
	 * Push a 16-bit return address, start at entry, and run until the code reaches stop16 in
	 * the bank of entry (a routine with inline arguments returns after them).
	 */
	private int call(long entry, long pushed16, long stop16, StepObserver observer) {
		setStackPointer(STACK_TOP - 2);
		writeMemory(STACK_TOP - 2, 2, pushed16);
		startAt(entry);
		return runUntil((entry & ~0xffffL) | stop16, observer);
	}

	private int runUntil(long stop, StepObserver observer) {
		return runUntil(stop, observer, MAX_STEPS);
	}

	private int runUntil(long stop, StepObserver observer, int maxSteps) {
		for (int steps = 0; steps < maxSteps; steps++) {
			long pc = thread.getCounter().getOffset();
			if (observer != null) {
				observer.at(pc, steps);
			}
			if (pc == stop) {
				return steps;
			}
			thread.stepInstruction();
		}
		throw new IllegalStateException(String.format("did not reach %05x in %d instructions; " +
			"stopped at %s", stop, maxSteps, thread.getCounter()));
	}

	// ---- direct calls ----

	private void writeResults(Path out, List<FunctionSpec> tables) throws Exception {
		Files.createDirectories(out.toAbsolutePath().getParent());
		int rows = 0;
		try (PrintWriter w =
			new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
			w.print(HEADER);
			for (FunctionSpec f : tables) {
				rows += callFunction(w, f);
			}
			for (String trace : list("traces")) {
				String[] ends = trace.split("-");
				rows += trace(w, Long.parseLong(ends[0], 16), Long.parseLong(ends[1], 16));
			}
		}
		println(String.format("wrote %d rows to %s; writes to R0: %d; descriptor calls that " +
			"decoded another table or x: %d", rows, out, r0Writes, descriptorDecodeErrors));
	}

	private int callFunction(PrintWriter w, FunctionSpec f) throws Exception {
		int rows = 0;
		for (long x : inputsFor(f)) {
			rows += callDirect(w, f, x);
			if (descriptorEntry >= 0 && f.width() == 1) {
				rows += callDescriptor(w, f, x);
			}
		}
		return rows;
	}

	private int callDirect(PrintWriter w, FunctionSpec f, long x) {
		long entry = f.width() == 1 ? byteEntry : wordEntry;
		if (entry < 0) {
			throw new IllegalArgumentException("give byte= or word= for " + f);
		}
		restoreRam();
		writeMemory(tableReg, 2, f.table() & 0xffff);
		writeMemory(xReg, f.width(), x);
		writeFlags(f.signedX(), f.signedY());
		int steps = callAt(entry, RETURN_MARK, null);
		checkR0();
		writeRow(w, "call", entry, entry, f, x, readMemory(resultReg, 2), steps);
		return 1;
	}

	/**
	 * Call the descriptor entry with two inline argument bytes (in the code bank) that hold the
	 * address of a record in RAM. The routine must decode the same table and x that the record
	 * gives when it reaches byte=; the script counts the calls where it does not.
	 */
	private int callDescriptor(PrintWriter w, FunctionSpec f, long x) {
		long record = descriptorWord(f.table());
		if (record < 0) {
			println(String.format("no base register for a descriptor of %05x", f.table()));
			return 0;
		}
		restoreRam();
		writeMemory(SCRATCH_RECORD, 2, record);
		writeMemory(SCRATCH_RECORD + 2, 2, SCRATCH_X);
		writeMemory(SCRATCH_X, 1, x);
		writeMemory((descriptorEntry & ~0xffffL) | SCRATCH_ARGS, 2, SCRATCH_RECORD);
		writeFlags(f.signedX(), f.signedY());
		long[] decoded = { -1, -1 };
		StepObserver capture = (pc, steps) -> {
			if (pc == byteEntry && decoded[0] < 0) {
				decoded[0] = bankedData(readMemory(tableReg, 2));
				decoded[1] = readMemory(xReg, 1);
			}
		};
		int steps = call(descriptorEntry, SCRATCH_ARGS, SCRATCH_ARGS + 2, capture);
		checkR0();
		if (decoded[0] != f.table() || decoded[1] != x) {
			descriptorDecodeErrors++;
		}
		writeRow(w, "descriptor", descriptorEntry, byteEntry, f, x, readMemory(resultReg, 2),
			steps);
		return 1;
	}

	/** The record word that selects base register 0xf0 + n and an offset, or -1. */
	private long descriptorWord(long table) {
		long table16 = table & 0xffff;
		long best = -1;
		for (int n = 0; n < 16; n += 2) {
			long offset = table16 - readMemory(0xf0 + n, 2);
			if (offset >= 0 && offset < 0x1000 && (best < 0 || offset < (best & 0xfff))) {
				best = ((long) n << 12) | offset;
			}
		}
		return best;
	}

	private void checkR0() {
		if (readMemory(0, 2) != 0) {
			r0Writes++;
			writeMemory(0, 2, 0);
		}
	}

	private void writeRow(PrintWriter w, String mode, long start, long routine, FunctionSpec f,
			long x, long result, int steps) {
		w.print(String.format("%s\t%x\t%x\t%05x\t%d\t%d\t%d\t%x\t%x\t%d\n", mode, start, routine,
			f.table(), f.width(), f.signedX() ? 1 : 0, f.signedY() ? 1 : 0, x, result, steps));
	}

	/** Every breakpoint x, the midpoints, the lowest and the highest x (and all with inputs=all). */
	private List<Long> inputsFor(FunctionSpec f) throws Exception {
		long max = f.width() == 1 ? 0xff : 0xffff;
		TreeSet<Long> xs = new TreeSet<>(List.of(0L, max));
		List<long[]> pairs = readPairs(f);
		for (int i = 0; i < pairs.size(); i++) {
			xs.add(pairs.get(i)[0]);
			if (i + 1 < pairs.size()) {
				xs.add((pairs.get(i)[0] + pairs.get(i + 1)[0]) / 2);
			}
		}
		if ("all".equals(args.get("inputs"))) {
			for (long x = 0; x <= max; x += f.width() == 1 ? 1 : 0x101) {
				xs.add(x);
			}
		}
		return new ArrayList<>(xs);
	}

	/** The (x, y) pairs of a function from program memory, up to the pair with the lowest x. */
	private List<long[]> readPairs(FunctionSpec f) throws Exception {
		long lowest = f.signedX() ? 0x80L << (8 * (f.width() - 1)) : 0;
		List<long[]> pairs = new ArrayList<>();
		for (int i = 0; i < MAX_PAIRS; i++) {
			long at = f.table() + 2L * f.width() * i;
			long[] pair = { readValue(at, f.width()), readValue(at + f.width(), f.width()) };
			pairs.add(pair);
			if (pair[0] == lowest) {
				return pairs;
			}
		}
		throw new IllegalArgumentException(String.format("no end of function at %05x", f.table()));
	}

	private long readValue(long address, int width) throws Exception {
		Address a = ram.getAddress(address);
		return width == 1 ? getByte(a) & 0xffL : getShort(a) & 0xffffL;
	}

	// ---- traces ----

	private int trace(PrintWriter w, long start, long end) {
		restoreRam();
		setStackPointer(STACK_TOP);
		startAt(start);
		TraceObserver observer = new TraceObserver(w, start);
		int steps = runUntil(end, observer);
		checkR0();
		println(String.format("trace %05x-%05x: %d instructions, %d lookups", start, end, steps,
			observer.rows));
		return observer.rows;
	}

	/** An entry into a lookup routine whose return has not been reached yet. */
	private record OpenCall(long routine, FunctionSpec f, long x, long returnTo, long sp,
			int steps) {
	}

	/** Records each entry into byte= or word= and the result at its return. */
	private class TraceObserver implements StepObserver {
		private final PrintWriter w;
		private final long start;
		private final Deque<OpenCall> open = new ArrayDeque<>();
		int rows;

		TraceObserver(PrintWriter w, long start) {
			this.w = w;
			this.start = start;
		}

		@Override
		public void at(long pc, int steps) {
			OpenCall top = open.peek();
			if (top != null && pc == top.returnTo() && stackPointerValue() == top.sp() + 2) {
				open.pop();
				writeRow(w, "trace", start, top.routine(), top.f(), top.x(),
					readMemory(resultReg, 2), steps - top.steps());
				rows++;
			}
			if ((pc == byteEntry || pc == wordEntry) && !insideLoop(pc)) {
				open.push(enter(pc, steps));
			}
		}

		/** True if the routine jumps back to its entry: the open call has the same stack. */
		private boolean insideLoop(long pc) {
			OpenCall top = open.peek();
			return top != null && top.routine() == pc && top.sp() == stackPointerValue();
		}

		private OpenCall enter(long pc, int steps) {
			int width = pc == byteEntry ? 1 : 2;
			FunctionSpec f = new FunctionSpec(bankedData(readMemory(tableReg, 2)), width,
				flagSet(SIGNED_X_BIT), flagSet(SIGNED_Y_BIT));
			long sp = stackPointerValue();
			long returnTo = (pc & ~0xffffL) | readMemory(sp, 2);
			return new OpenCall(pc, f, readMemory(xReg, width), returnTo, sp, steps);
		}
	}

	// ---- references ----

	private void findReferences(Path out, List<FunctionSpec> tables) throws Exception {
		List<Target> targets = targets(tables);
		classifyAccesses();
		readInlineArguments();
		List<String> rows = new ArrayList<>();
		rows.addAll(ghidraReferences(targets));
		rows.addAll(codeRows(targets, codeValues, "code-value"));
		rows.addAll(codeRows(targets, codeLoads, "code-load"));
		rows.addAll(callSiteLookups(targets));
		rows.addAll(dataRows(targets));
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.write(out, prepend("kind\tat\ttarget\ttable\tdetail", rows), StandardCharsets.UTF_8);
		println(String.format("wrote %d references to %s", rows.size(), out));
	}

	/** Each function (its pairs, or span= bytes), and the bytes just before it (margin=). */
	private List<Target> targets(List<FunctionSpec> tables) throws Exception {
		long margin = Long.parseLong(args.getOrDefault("margin", "2"));
		long span = Long.parseLong(args.getOrDefault("span", "0"), 16);
		List<Target> targets = new ArrayList<>();
		for (FunctionSpec f : tables) {
			long size = Math.max(span, 2L * f.width() * readPairs(f).size());
			targets.add(new Target(f.table(), f.table() - margin, f.table() + size));
		}
		return targets;
	}

	private static List<String> prepend(String header, List<String> rows) {
		List<String> all = new ArrayList<>();
		all.add(header);
		all.addAll(rows);
		return all;
	}

	private static void addTo(TreeMap<Long, List<Long>> map, long key, long where) {
		List<Long> list = map.computeIfAbsent(key, k -> new ArrayList<>());
		if (!list.contains(where)) {
			list.add(where);
		}
	}

	private Target targetOf(List<Target> targets, long address) {
		for (Target t : targets) {
			if (address >= t.start() && address < t.end()) {
				return t;
			}
		}
		return null;
	}

	private String row(String kind, long at, long target, Target t, String detail) {
		return String.format("%s\t%05x\t%05x\t%05x%+d\t%s", kind, at, target, t.table(),
			target - t.table(), detail);
	}

	private String instructionText(long address) {
		Instruction ins = getInstructionAt(ram.getAddress(address));
		return ins == null ? "" : ins.toString();
	}

	/** References that Ghidra's analysis made to an address in a target. */
	private List<String> ghidraReferences(List<Target> targets) {
		List<String> rows = new ArrayList<>();
		for (Target t : targets) {
			for (long a = t.start(); a < t.end(); a++) {
				for (Reference ref : getReferencesTo(ram.getAddress(a))) {
					long from = ref.getFromAddress().getOffset();
					rows.add(row("ghidra-ref", from, a, t,
						ref.getReferenceType() + " " + instructionText(from)));
				}
			}
		}
		return rows;
	}

	/** Rows for the code values or loads that are in a target. */
	private List<String> codeRows(List<Target> targets, TreeMap<Long, List<Long>> map,
			String kind) {
		List<String> rows = new ArrayList<>();
		for (Map.Entry<Long, List<Long>> e : map.entrySet()) {
			Target t = targetOf(targets, e.getKey());
			for (long at : t == null ? List.<Long> of() : e.getValue()) {
				rows.add(row(kind, at, e.getKey(), t, instructionText(at)));
			}
		}
		return rows;
	}

	// ---- references: basic blocks ----

	/** A memory access of one instruction: a word written ("value") or a load. */
	private record Access(String kind, long address, long value) {
	}

	/**
	 * Run every basic block twice in the emulator: first with the RAM after init, then with
	 * every RAM byte that is 0 after init set to 2. A word value or a load address that is the
	 * same in both runs depends only on constants and on registers that init set; a load address
	 * that changes depends on another register (an index into a list).
	 */
	private void classifyAccesses() throws Exception {
		byte[] filled = ramAfterInit.clone();
		for (int i = 2; i < filled.length; i++) {
			filled[i] = filled[i] == 0 ? 2 : filled[i];
		}
		Map<Long, List<Access>> first = emulateBlocks(ramAfterInit);
		Map<Long, List<Access>> second = emulateBlocks(filled);
		for (Map.Entry<Long, List<Access>> e : first.entrySet()) {
			List<Access> other = second.getOrDefault(e.getKey(), List.of());
			for (int i = 0; i < e.getValue().size() && i < other.size(); i++) {
				classify(e.getKey(), e.getValue().get(i), other.get(i));
			}
		}
		println(String.format("blocks: %s; constant values %d, constant loads %d, list loads %d",
			blockFailures.isEmpty() ? "all ran" : "stopped early " + blockFailures,
			codeValues.size(), codeLoads.size(), listLoads.size()));
	}

	private void classify(long at, Access a, Access b) {
		boolean toStack = a.address() > STACK_TOP - 0x100 && a.address() <= STACK_TOP;
		if (!a.kind().equals(b.kind())) {
			return;
		}
		if (a.kind().equals("value") && !toStack && a.address() == b.address() &&
			a.value() == b.value()) {
			addTo(codeValues, a.value(), at);
		}
		else if (a.kind().equals("load")) {
			addTo(a.address() == b.address() ? codeLoads : listLoads, a.address(), at);
		}
	}

	/** Emulate every basic block from the given RAM; the accesses of each instruction. */
	private Map<Long, List<Access>> emulateBlocks(byte[] startRam) throws Exception {
		Map<Long, List<Access>> accesses = new HashMap<>();
		recorder.listener = (at, kind, address, value) -> {
			if (at >= 0) {
				long v = kind.equals("value") ? bankedData(value) : value;
				accesses.computeIfAbsent(at, k -> new ArrayList<>()).add(new Access(kind, address, v));
			}
		};
		CodeBlockIterator it = new SimpleBlockModel(currentProgram).getCodeBlocks(monitor);
		while (it.hasNext()) {
			runBlock(it.next(), startRam);
		}
		recorder.listener = null;
		return accesses;
	}

	/** Emulate the instructions of one block. */
	private void runBlock(CodeBlock block, byte[] startRam) {
		Address min = block.getMinAddress();
		if (getInstructionAt(min) == null) {
			return;
		}
		recorder.current = -1;   // the writes below are not made by an instruction
		emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, startRam.clone());
		setStackPointer(STACK_TOP);
		startAt(min.getOffset());
		try {
			for (int i = 0; i < BLOCK_STEPS && block.contains(thread.getCounter()); i++) {
				thread.stepInstruction();
			}
		}
		catch (RuntimeException e) {
			// For example a divide by 0 with an unknown register, or a user operation (NORML)
			// that the emulator does not have.
			String reason = String.valueOf(e.getMessage()).replaceAll(" in the library \\S+", "");
			blockFailures.merge(reason, 1, Integer::sum);
			thread.dropInstruction();
		}
	}

	// ---- references: call sites ----

	/**
	 * Run every basic block that ends with a call, from the RAM after init, through the call
	 * until the code returns after it, and report each entry into byte= or word= with a table
	 * in a target. The registers that the block does not set are 0 (from init otherwise).
	 */
	private List<String> callSiteLookups(List<Target> targets) throws Exception {
		List<String> rows = new ArrayList<>();
		int sites = 0;
		CodeBlockIterator it = new SimpleBlockModel(currentProgram).getCodeBlocks(monitor);
		while (it.hasNext()) {
			CodeBlock block = it.next();
			Instruction last = getInstructionContaining(block.getMaxAddress());
			if (last != null && last.getFlowType().isCall() && last.getFallThrough() != null) {
				sites++;
				rows.addAll(traceCallSite(block, last, targets));
			}
		}
		println(String.format("call sites run: %d", sites));
		return rows;
	}

	private List<String> traceCallSite(CodeBlock block, Instruction call, List<Target> targets) {
		List<String> rows = new ArrayList<>();
		long start = block.getMinAddress().getOffset();
		restoreRam();
		setStackPointer(STACK_TOP);
		startAt(start);
		TraceCollector collector = new TraceCollector();
		try {
			runUntil(call.getFallThrough().getOffset(), collector, CALL_SITE_STEPS);
		}
		catch (RuntimeException e) {
			thread.dropInstruction();   // a callee loops or waits for hardware; keep what ran
		}
		for (FunctionSpec f : collector.tables) {
			Target t = targetOf(targets, f.table());
			if (t != null) {
				rows.add(row("call-site", call.getAddress().getOffset(), f.table(), t,
					String.format("block %05x; %s", start, instructionText(call.getAddress().getOffset()))));
			}
		}
		return rows;
	}

	/** Collects the table register at each entry into byte= or word=. */
	private class TraceCollector implements StepObserver {
		final List<FunctionSpec> tables = new ArrayList<>();
		private long lastEntrySp = -1;

		@Override
		public void at(long pc, int steps) {
			if (pc != byteEntry && pc != wordEntry) {
				return;
			}
			long sp = stackPointerValue();
			if (sp == lastEntrySp) {
				return;   // the routine jumps back to its entry
			}
			lastEntrySp = sp;
			tables.add(new FunctionSpec(bankedData(readMemory(tableReg, 2)),
				pc == byteEntry ? 1 : 2, flagSet(SIGNED_X_BIT), flagSet(SIGNED_Y_BIT)));
		}
	}

	// ---- references: data ----

	/**
	 * The words between a call and its overridden fall-through (inline arguments, as
	 * EecFindCode.java marks them), as banked data addresses.
	 */
	private void readInlineArguments() throws Exception {
		for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
			if (!ins.isFallThroughOverridden() || ins.getFallThrough() == null) {
				continue;
			}
			long end = ins.getMaxAddress().getOffset() + 1;
			long bank = dataBankAt(ins.getAddress());
			for (long a = end; a + 1 < ins.getFallThrough().getOffset(); a += 2) {
				long word = readValue(a, 2);
				addTo(inlineWords, word >= ROM_START ? bank + word : word, a);
			}
		}
	}

	/** The data bank (bank * 0x10000) that the program context gives an address. */
	private long dataBankAt(Address a) {
		if (dataBank == null) {
			return 0;
		}
		BigInteger bank = contextAt(a).getRegisterValue(dataBank).getUnsignedValue();
		return bank == null ? 0 : bank.longValue() << 16;
	}

	/**
	 * Words outside instructions that are the 16-bit address of a target, its offset from a
	 * register that init set to a ROM address, or a descriptor record word for it. Only words
	 * that code uses are reported (see codeUsers); the others are counted. Words that Ghidra
	 * marks as code addresses (a reference from the word to an instruction) are left out.
	 */
	private List<String> dataRows(List<Target> targets) throws Exception {
		Map<Long, List<Object[]>> wanted = wantedWords(targets);
		List<String> rows = new ArrayList<>();
		int found = 0;
		for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
			if (!block.isInitialized() || block.getStart().getOffset() < ROM_START) {
				continue;
			}
			for (long a = block.getStart().getOffset(); a < block.getEnd().getOffset(); a++) {
				List<Object[]> meanings = wanted.get(readValue(a, 2));
				if (meanings != null && isDataWord(a)) {
					found += meanings.size();
					addDataRows(rows, a, meanings, codeUsers(a));
				}
			}
		}
		println(String.format("data words that match a target: %d; used by code: %d", found,
			rows.size()));
		return rows;
	}

	/** Not inside an instruction, and not a code address (no reference from it to code). */
	private boolean isDataWord(long a) {
		Address address = ram.getAddress(a);
		if (getInstructionContaining(address) != null) {
			return false;
		}
		for (Reference ref : getReferencesFrom(address)) {
			if (getInstructionAt(ref.getToAddress()) != null) {
				return false;
			}
		}
		return true;
	}

	private void addDataRows(List<String> rows, long a, List<Object[]> meanings, String users) {
		if (users.isEmpty()) {
			return;
		}
		for (Object[] m : meanings) {
			rows.add(row((String) m[0], a, (Long) m[1], (Target) m[2], m[3] + "; used by " + users));
		}
	}

	/** Word values to look for, each with its kind, target address, target and detail. */
	private Map<Long, List<Object[]>> wantedWords(List<Target> targets) {
		Map<Long, List<Object[]>> wanted = new HashMap<>();
		Map<Long, Long> pointers = romPointerRegisters();
		for (Target t : targets) {
			for (long a = t.start(); a < t.end(); a++) {
				long a16 = a & 0xffff;
				put(wanted, a16, "data-address", a, t, "16-bit address");
				for (Map.Entry<Long, Long> p : pointers.entrySet()) {
					addOffsetWords(wanted, a, t, p.getKey(), a16 - p.getValue());
				}
			}
		}
		return wanted;
	}

	private void addOffsetWords(Map<Long, List<Object[]>> wanted, long a, Target t, long reg,
			long offset) {
		if (offset < 0 || offset >= 0x1000) {
			return;
		}
		String name = String.format("R%02x", reg);
		put(wanted, offset, "data-offset", a, t, "offset from " + name);
		if (reg >= 0xf0) {
			put(wanted, ((reg - 0xf0) << 12) | offset, "data-descriptor", a, t,
				"descriptor word for " + name);
		}
	}

	private static void put(Map<Long, List<Object[]>> wanted, long word, String kind, long target,
			Target t, String detail) {
		wanted.computeIfAbsent(word, k -> new ArrayList<>())
				.add(new Object[] { kind, target, t, detail });
	}

	/** Registers (0x22-0xff, even) that hold a ROM address after init: register -> value. */
	private Map<Long, Long> romPointerRegisters() {
		Map<Long, Long> pointers = new TreeMap<>();
		for (long r = 0x22; r < 0x100; r += 2) {
			long value = (ramAfterInit[(int) r] & 0xffL) | (ramAfterInit[(int) r + 1] & 0xffL) << 8;
			if (value >= ROM_START) {
				pointers.put(r, value);
			}
		}
		return pointers;
	}

	/**
	 * How code uses the word at address a: references from instructions, code that forms the
	 * address from constants or loads from it, an indexed load of a list that starts up to 0x40
	 * bytes before it (at an even distance), and inline arguments that hold the address.
	 */
	private String codeUsers(long a) {
		List<String> users = new ArrayList<>();
		for (Reference ref : getReferencesTo(ram.getAddress(a))) {
			if (getInstructionAt(ref.getFromAddress()) != null) {
				users.add(String.format("reference from %05x", ref.getFromAddress().getOffset()));
			}
		}
		addUsers(users, "address formed at", codeValues.get(a));
		addUsers(users, "load at", codeLoads.get(a));
		addUsers(users, "inline argument at", inlineWords.get(a));
		Map.Entry<Long, List<Long>> list = listLoads.floorEntry(a);
		if (list != null && a - list.getKey() < 0x40 && (a - list.getKey()) % 2 == 0) {
			addUsers(users, String.format("indexed load of the list at %05x at", list.getKey()),
				list.getValue());
		}
		return String.join("; ", users);
	}

	private static void addUsers(List<String> users, String what, List<Long> where) {
		if (where != null) {
			StringBuilder sb = new StringBuilder(what);
			for (long w : where) {
				sb.append(String.format(" %05x", w));
			}
			users.add(sb.toString());
		}
	}

	// ---- emulator callbacks ----

	/**
	 * Receives memory accesses with the address of the instruction (-1 if no instruction made
	 * it): kind "value" for a word written to address, kind "load" for a load from address.
	 */
	private interface AccessListener {
		void access(long instruction, String kind, long address, long value);
	}

	private class Recorder implements PcodeEmulationCallbacks<byte[]> {
		long current = -1;
		AccessListener listener;

		@Override
		public void beforeExecuteInstruction(PcodeThread<byte[]> t, Instruction instruction,
				PcodeProgram program) {
			current = instruction.getAddress().getOffset();
		}

		// The byte state reports writes with a concrete address.
		@Override
		public <A, U> void dataWritten(PcodeThread<byte[]> t, PcodeExecutorStatePiece<A, U> piece,
				Address address, int length, U value) {
			if (listener != null && length == 2 && address.getAddressSpace().equals(ram)) {
				listener.access(current, "value", address.getOffset(),
					piece.getArithmetic().toLong(value, Purpose.INSPECT));
			}
		}

		@Override
		public void afterLoad(PcodeThread<byte[]> t, PcodeOp op, AddressSpace space, byte[] offset,
				int size, byte[] value) {
			if (listener != null && space.equals(ram)) {
				listener.access(current, "load", arith.toLong(offset, Purpose.LOAD),
					arith.toLong(value, Purpose.INSPECT));
			}
		}
	}
}
