// Lists the memory accesses of every instruction of an EEC-IV program, so that the code that
// reads or writes a RAM variable or a calibration structure can be found with a text search.
//
// For each instruction the script writes one row per access:
//   direct operands: the RAM-space varnodes of the instruction's p-code (registers 0x00-0xff
//                    and absolute addresses), read or written;
//   indirect and indexed operands: the address of each LOAD and STORE of the p-code. The
//                    script runs the instruction twice in Ghidra's p-code emulator, from the RAM
//                    after the init routines: first with every other RAM byte (see keep=) set
//                    to 1, then set to 2. An address that is the same in both runs depends only
//                    on constants and on the kept bytes (for example a base register and an
//                    offset); it is reported as "const". An address that changes depends
//                    on another register (a pointer or an index) and is reported as "var" with
//                    the address of the first run.
//
// Arguments (key=value). Addresses are banked hex: bank * 0x10000 + 16-bit address.
//   out=<path>          result rows, tab-separated (required)
//   init=<a>,<b>        subroutines to run first, in this order, each until it returns, for
//                       example the routines that load the base registers
//   keep=<a>-<b>,...    RAM bytes that keep their value from init in both runs (hex ranges,
//                       for example the base registers). Default: every byte that init wrote.
//                       Give it when init also writes scratch registers.
//   from=<a> to=<a>     only instructions in this range (default: all instructions)
//
// Rows (header: at kind mode address size instruction):
//   at       address of the instruction
//   kind     rd or wr; or val: a word that the instruction writes to RAM, that is the same in
//            both runs and is 0x2000 or more (a ROM address that the code forms from constants
//            and init values, for example AD3W R3c,Rf0,#0x77e); address is the value as an
//            address in the data bank of the instruction
//   mode     direct, const or var (see above)
//   address  the address read or written (banked for ROM addresses)
//   size     bytes
//
// Limits: in the p-code the stack pointer SP is not the RAM word StackPtr (0x20); pushes and
// pops are left out (stores and loads within 0x100 bytes below the emulated stack top).
// Hardware registers read 0. An instruction that the emulator cannot run (for example a divide
// by 0 or a user operation without an implementation) is reported with mode "failed".
//@category EEC

import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ghidra.app.script.GhidraScript;
import ghidra.pcode.emu.PcodeEmulationCallbacks;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeArithmetic;
import ghidra.pcode.exec.PcodeArithmetic.Purpose;
import ghidra.pcode.exec.PcodeExecutorStatePiece;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

public class EecAccessMap extends GhidraScript {

	private static final int RAM_SIZE = 0x2000;
	private static final long ROM_START = 0x2000;
	private static final long STACK_TOP = 0x1f00;
	private static final long RETURN_MARK = 0x1ffe;
	private static final int MAX_STEPS = 100000;

	private Map<String, String> args;
	private PcodeEmulator emu;
	private PcodeThread<byte[]> thread;
	private PcodeArithmetic<byte[]> arith;
	private AddressSpace ram;
	private Register stackPointer;
	private final Recorder recorder = new Recorder();
	private final Set<Long> initWrites = new HashSet<>();
	private final List<long[]> keepRanges = new ArrayList<>();
	private boolean recordInit;

	/** One LOAD or STORE of an emulated instruction, or a word value that it wrote. */
	private record Access(String kind, long address, int size, long value) {
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
		for (String item : args.getOrDefault("keep", "").split(",")) {
			if (!item.isBlank()) {
				String[] ends = item.trim().split("-");
				long lo = Long.parseLong(ends[0], 16);
				keepRanges.add(new long[] { lo, ends.length > 1 ? Long.parseLong(ends[1], 16) : lo });
			}
		}
		setUpEmulator();
		recordInit = true;
		for (String item : args.getOrDefault("init", "").split(",")) {
			if (!item.isBlank()) {
				long entry = Long.parseLong(item.trim(), 16);
				int steps = callAt(entry);
				println(String.format("init %05x: returned after %d instructions", entry, steps));
			}
		}
		recordInit = false;
		byte[] afterInit = readRam();
		byte[] firstRam = fill(afterInit, 1);
		byte[] secondRam = fill(afterInit, 2);
		long from = Long.parseLong(args.getOrDefault("from", "0"), 16);
		long to = Long.parseLong(args.getOrDefault("to", "ffffff"), 16);
		int rows = 0, instructions = 0, failed = 0;
		Path path = Path.of(out);
		Files.createDirectories(path.toAbsolutePath().getParent());
		try (PrintWriter w =
			new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
			w.print("at\tkind\tmode\taddress\tsize\tinstruction\n");
			for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
				long at = ins.getAddress().getOffset();
				if (at < from || at > to) {
					continue;
				}
				instructions++;
				String text = ins.toString();
				for (String row : directAccesses(ins)) {
					w.print(String.format("%05x\t%s\t%s\n", at, row, text));
					rows++;
				}
				List<Access> first = emulate(ins, firstRam);
				List<Access> second = emulate(ins, secondRam);
				if (first == null || second == null) {
					w.print(String.format("%05x\t-\tfailed\t-\t-\t%s\n", at, text));
					failed++;
					continue;
				}
				for (int i = 0; i < first.size(); i++) {
					Access a = first.get(i);
					Access b = i < second.size() ? second.get(i) : null;
					boolean same = b != null && b.address() == a.address() &&
						b.kind().equals(a.kind());
					if (a.kind().equals("val")) {
						// A word written to RAM that is the same in both runs and is a ROM
						// address: an address that the code forms from constants.
						if (same && b.value() == a.value() && a.value() >= ROM_START) {
							w.print(String.format("%05x\tval\tconst\t%05x\t2\t%s\n", at,
								dataBankAt(ins.getAddress()) + a.value(), text));
							rows++;
						}
						continue;
					}
					w.print(String.format("%05x\t%s\t%s\t%05x\t%d\t%s\n", at, a.kind(),
						same ? "const" : "var", a.address(), a.size(), text));
					rows++;
				}
			}
		}
		println(String.format("instructions %d, rows %d, instructions the emulator could not run %d",
			instructions, rows, failed));
	}

	/**
	 * The RAM after init with every byte that init did not write set to value (R0 stays 0).
	 * A value other than 0 keeps divisions by a register that init did not set from stopping
	 * the emulator.
	 */
	private byte[] fill(byte[] afterInit, int value) {
		byte[] filled = afterInit.clone();
		for (int i = 2; i < filled.length; i++) {
			if (!kept(i)) {
				filled[i] = (byte) value;
			}
		}
		return filled;
	}

	/**
	 * True if the RAM byte keeps its value from init in both runs: a byte in keep= if that
	 * argument is given, otherwise a byte that init wrote. Give keep= when init also uses
	 * other registers as scratch: an address formed from a scratch register is not constant.
	 */
	private boolean kept(long address) {
		if (keepRanges.isEmpty()) {
			return initWrites.contains(address);
		}
		for (long[] r : keepRanges) {
			if (address >= r[0] && address <= r[1]) {
				return true;
			}
		}
		return false;
	}

	/** Reads and writes of RAM-space varnodes in the p-code of the instruction. */
	private List<String> directAccesses(Instruction ins) {
		List<String> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (PcodeOp op : ins.getPcode()) {
			for (int i = 0; i < op.getNumInputs(); i++) {
				int code = op.getOpcode();
				if ((code == PcodeOp.LOAD || code == PcodeOp.STORE) && i == 0) {
					continue;   // the space id
				}
				if ((code == PcodeOp.BRANCH || code == PcodeOp.CBRANCH || code == PcodeOp.CALL) &&
					i == 0) {
					continue;   // the target of a jump or call
				}
				addDirect(rows, seen, "rd", op.getInput(i));
			}
			addDirect(rows, seen, "wr", op.getOutput());
		}
		return rows;
	}

	private void addDirect(List<String> rows, Set<String> seen, String kind, Varnode v) {
		if (v == null || !v.isAddress() || !v.getAddress().getAddressSpace().equals(ram)) {
			return;
		}
		String row = String.format("%s\tdirect\t%05x\t%d", kind, v.getOffset(), v.getSize());
		if (seen.add(row)) {
			rows.add(row);
		}
	}

	/** Run one instruction from the given RAM and return its LOADs and STOREs, or null. */
	private List<Access> emulate(Instruction ins, byte[] startRam) {
		List<Access> accesses = new ArrayList<>();
		emu.getSharedState().setVar(ram.getAddress(0), RAM_SIZE, false, startRam.clone());
		setStackPointer(STACK_TOP);
		startAt(ins.getAddress());
		recorder.accesses = accesses;
		try {
			thread.stepInstruction();
		}
		catch (RuntimeException e) {
			thread.dropInstruction();
			return null;
		}
		finally {
			recorder.accesses = null;
		}
		accesses.removeIf(a -> a.address() > STACK_TOP - 0x100 && a.address() <= STACK_TOP);
		return accesses;
	}

	/** The data bank (bank * 0x10000) that the program context gives an address, or 0. */
	private long dataBankAt(Address a) {
		Register dataBank = currentProgram.getRegister("dbank");
		if (dataBank == null) {
			return 0;
		}
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		if (ins != null) {
			context = context.combineValues(ins.getRegisterValue(base));
		}
		BigInteger bank = context.getRegisterValue(dataBank).getUnsignedValue();
		return bank == null ? 0 : bank.longValue() << 16;
	}

	// ---- emulator state (as in EecEmulatorCheck.java) ----

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

	private byte[] readRam() {
		return emu.getSharedState().getVar(ram.getAddress(0), RAM_SIZE, false, Reason.INSPECT);
	}

	private void writeMemory(long address, int size, long value) {
		emu.getSharedState().setVar(ram.getAddress(address), size, false,
			arith.fromConst(value, size));
	}

	private void setStackPointer(long value) {
		thread.getState().setVar(stackPointer,
			arith.fromConst(value, stackPointer.getMinimumByteSize()));
		Register ramWord = currentProgram.getRegister("StackPtr");
		if (ramWord != null && ramWord.getAddress().getAddressSpace().equals(ram)) {
			writeMemory(ramWord.getAddress().getOffset(), 2, value);
		}
	}

	private void startAt(Address a) {
		thread.overrideCounter(a);
		Register base = currentProgram.getLanguage().getContextBaseRegister();
		RegisterValue context = new RegisterValue(base, BigInteger.ZERO)
				.combineValues(currentProgram.getProgramContext().getDefaultValue(base, a));
		Instruction ins = getInstructionAt(a);
		thread.overrideContext(ins == null ? context : context.combineValues(ins.getRegisterValue(base)));
	}

	/** Push a return mark, start at entry and run until the return. */
	private int callAt(long entry) {
		setStackPointer(STACK_TOP - 2);
		writeMemory(STACK_TOP - 2, 2, RETURN_MARK);
		startAt(ram.getAddress(entry));
		long stop = (entry & ~0xffffL) | RETURN_MARK;
		for (int steps = 0; steps < MAX_STEPS; steps++) {
			if (thread.getCounter().getOffset() == stop) {
				return steps;
			}
			thread.stepInstruction();
		}
		throw new IllegalStateException(String.format("init %05x did not return", entry));
	}

	private class Recorder implements PcodeEmulationCallbacks<byte[]> {
		List<Access> accesses;

		@Override
		public <A, U> void dataWritten(PcodeThread<byte[]> t, PcodeExecutorStatePiece<A, U> piece,
				Address address, int length, U value) {
			if (!address.getAddressSpace().equals(ram) || address.getOffset() >= RAM_SIZE) {
				return;
			}
			if (recordInit) {
				for (int i = 0; i < length; i++) {
					initWrites.add(address.getOffset() + i);
				}
			}
			if (accesses != null && length == 2) {
				accesses.add(new Access("val", address.getOffset(), 2,
					piece.getArithmetic().toLong(value, Purpose.INSPECT)));
			}
		}

		@Override
		public void afterLoad(PcodeThread<byte[]> t, PcodeOp op, AddressSpace space, byte[] offset,
				int size, byte[] value) {
			if (accesses != null && space.equals(ram)) {
				accesses.add(new Access("rd", arith.toLong(offset, Purpose.LOAD), size, 0));
			}
		}

		@Override
		public void beforeStore(PcodeThread<byte[]> t, PcodeOp op, AddressSpace space,
				byte[] offset, int size, byte[] value) {
			if (accesses != null && space.equals(ram)) {
				accesses.add(new Access("wr", arith.toLong(offset, Purpose.STORE), size, 0));
			}
		}
	}
}
