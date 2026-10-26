// Finds EEC-IV code that flow analysis from the entry points does not reach. It uses four
// rules that SAD (the Semi Automatic Disassembler) also uses:
//
// 1. Code tables. PUSHW #imm pushes a code address (usually a return address). PUSHW [Rx]
//    and PUSHW imm[Rx] push an entry of a table of 16-bit code addresses, which is usually
//    in the data bank; for [Rx] the script looks back in the same block for LDW, AD2W or
//    AD3W that loads the table address into Rx. The script disassembles every table entry,
//    and stops at the first entry that is not a code address of the bank or that does not
//    decode. It adds a reference from each table entry to its code.
//
// 2. Inline arguments. Some subroutines pop their return address into a register, read the
//    argument bytes that follow the CALL with [Rx]+, and push the new return address. The
//    script finds them, marks them as functions that do not return, sets the fall-through of
//    each call past the argument bytes, and marks the argument bytes as data.
//
// 3. Code gaps. Bytes that no rule has decoded, between code and code, and that end with
//    the byte of RET or RETI (f0, f1), are decoded as code. SAD does the same (scan_cgap in
//    SAD 4.13 chain.cpp): this finds subroutines that nothing in the code refers to. A gap
//    is not decoded if the code refers to an address in it as data, or if a constant operand
//    (other than of PUSHW) is the address of its first byte, as in LDW Rx,#0x4000 for a
//    table at 0x4000. The decoding is undone if it gives a decode error, or if the code it
//    decodes in the gap does not end with a return or a jump. The rest of the gap is then a
//    new gap, which the next round tries.
//
// 4. Skipped bytes. SKIP jumps over the next byte, which code can also jump to. If that
//    byte is a one-byte instruction, it is decoded, as SAD lists it.
//
// The script repeats the rules until it finds no more code. When it finds an
// inline-argument subroutine after Ghidra has already decoded argument bytes as
// instructions, it clears the code and disassembles again from the entry points and the
// code it found before.
//
// Run it after EecLoadBanks.java. These rules are guesses that work for Ford EEC-IV code;
// check the result.
//@category EEC

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.address.AddressRange;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.data.ArrayDataType;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Bookmark;
import ghidra.program.model.listing.BookmarkType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.SourceType;

public class EecFindCode extends GhidraScript {

	private static final int MAX_ROUNDS = 40;
	private static final int MAX_TABLE_ENTRIES = 128;
	private static final int ROM_START = 0x2000;
	private static final Pattern HEX = Pattern.compile("^0x([0-9a-f]+)$");
	private static final Pattern IMMEDIATE = Pattern.compile("^#0x([0-9a-f]+)$");
	private static final Pattern AUTO_INCREMENT = Pattern.compile("^\\[(.+)\\]\\+$");
	private static final Pattern INDIRECT = Pattern.compile("^\\[(.+)\\]$");
	private static final Pattern INDEXED = Pattern.compile("^0x([0-9a-f]+)\\[(.+)\\]$");

	private final Map<Address, Integer> argumentBytes = new TreeMap<>();
	private final Set<Address> checkedTargets = new LinkedHashSet<>();
	private final Set<Address> foundStarts = new LinkedHashSet<>();
	private final Set<Long> readTables = new LinkedHashSet<>();
	private final Set<String> triedGaps = new LinkedHashSet<>();
	private Set<Long> dataConstants = new LinkedHashSet<>();
	private Listing listing;
	private int restarts;

	@Override
	protected void run() throws Exception {
		listing = currentProgram.getListing();
		for (int round = 1; round <= MAX_ROUNDS; round++) {
			int newSubs = findArgumentSubroutines();
			if (newSubs > 0 && argumentBytesDecoded()) {
				restart();
				continue;
			}
			boolean more = resumeAfterArgumentCalls();
			more |= followCodeTables();
			if (!more) {
				more = scanCodeGaps();
			}
			if (newSubs == 0 && !more) {
				break;
			}
		}
		decodeSkippedBytes();
		println(String.format(
			"inline-argument subroutines: %d %s; calls fixed: %d; tables read: %d; " +
				"code starts found: %d; restarts: %d",
			argumentBytes.size(), argumentBytes, countFixedCalls(), readTables.size(),
			foundStarts.size(), restarts));
	}

	// ---- inline arguments ----

	private int findArgumentSubroutines() throws Exception {
		int count = 0;
		for (Instruction call : instructions()) {
			if (!call.getFlowType().isCall()) {
				continue;
			}
			for (Address target : call.getFlows()) {
				Instruction first = listing.getInstructionAt(target);
				if (first == null || !checkedTargets.add(target)) {
					continue;
				}
				int bytes = argumentByteCount(first);
				if (bytes > 0) {
					argumentBytes.put(target, bytes);
					markNoReturn(target, bytes);
					count++;
				}
			}
		}
		return count;
	}

	/** Bytes of inline arguments that the subroutine reads, or 0 if it does not. */
	private int argumentByteCount(Instruction first) {
		long pointer = -1;
		int bytes = 0;
		Instruction ins = first;
		for (int i = 0; i < 16 && ins != null; i++) {
			String m = ins.getMnemonicString().toUpperCase();
			if (pointer < 0 && m.equals("POPW")) {
				pointer = registerOffset(operand(ins, 0));
				if (pointer < 0) {
					return 0;
				}
			}
			else if (pointer >= 0 && m.equals("PUSHW") && registerOffset(operand(ins, 0)) == pointer) {
				return bytes;
			}
			else if (isUnconditionalJump(ins)) {
				ins = listing.getInstructionAt(ins.getFlows()[0]);   // for example SJMP to the POPW
				continue;
			}
			else if (!fallsThroughOnly(ins)) {
				return 0;
			}
			else if (pointer >= 0) {
				bytes += autoIncrementBytes(ins, pointer);
			}
			ins = fallThroughInstruction(ins);
		}
		return 0;
	}

	private boolean isUnconditionalJump(Instruction ins) {
		return ins.getFlowType().isJump() && ins.getFlowType().isUnConditional() &&
			ins.getFlows().length == 1;
	}

	private boolean fallsThroughOnly(Instruction ins) {
		String m = ins.getMnemonicString().toUpperCase();
		return ins.getFlowType().isFallthrough() && !m.startsWith("PUSH") && !m.startsWith("POP");
	}

	private int autoIncrementBytes(Instruction ins, long pointer) {
		int bytes = 0;
		for (int i = 0; i < ins.getNumOperands(); i++) {
			Matcher m = AUTO_INCREMENT.matcher(operand(ins, i));
			if (m.matches() && registerOffset(m.group(1)) == pointer) {
				bytes += isByteOperation(ins) ? 1 : 2;
			}
		}
		return bytes;
	}

	private boolean isByteOperation(Instruction ins) {
		String m = ins.getMnemonicString().toUpperCase();
		return m.endsWith("B") || m.equals("LDBZE") || m.equals("LDBSE");
	}

	private void markNoReturn(Address entry, int bytes) throws Exception {
		Function f = getFunctionAt(entry);
		if (f == null) {
			f = createFunction(entry, null);
		}
		if (f == null) {
			println("could not create a function at " + entry + "; its calls are not fixed");
			return;
		}
		f.setNoReturn(true);
		f.setComment("Reads " + bytes + " bytes of inline arguments that follow each call, " +
			"and returns after them (found by EecFindCode.java).");
	}

	/** True if Ghidra decoded the argument bytes after a call as instructions. */
	private boolean argumentBytesDecoded() {
		for (Address entry : argumentBytes.keySet()) {
			for (Instruction call : callsTo(entry)) {
				Address next = call.getMaxAddress().next();
				if (!call.isFallThroughOverridden() && listing.getInstructionContaining(next) != null) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean resumeAfterArgumentCalls() throws Exception {
		boolean more = false;
		for (Map.Entry<Address, Integer> e : argumentBytes.entrySet()) {
			for (Instruction call : callsTo(e.getKey())) {
				Address next = call.getMaxAddress().next();
				if (call.isFallThroughOverridden() || listing.getInstructionContaining(next) != null) {
					continue;
				}
				Address resume = next.add(e.getValue());
				call.setFallThrough(resume);
				markArgumentData(next, e.getValue());
				more |= startCode(resume);
			}
		}
		return more;
	}

	private void markArgumentData(Address start, int bytes) {
		try {
			createData(start, new ArrayDataType(ByteDataType.dataType, bytes, 1));
		}
		catch (Exception e) {
			println("could not mark inline arguments at " + start + ": " + e.getMessage());
		}
	}

	private int countFixedCalls() {
		int n = 0;
		for (Address entry : argumentBytes.keySet()) {
			for (Instruction call : callsTo(entry)) {
				n += call.isFallThroughOverridden() ? 1 : 0;
			}
		}
		return n;
	}

	private List<Instruction> callsTo(Address entry) {
		List<Instruction> calls = new ArrayList<>();
		for (Reference ref : getReferencesTo(entry)) {
			Instruction ins = listing.getInstructionAt(ref.getFromAddress());
			if (ref.getReferenceType().isCall() && ins != null) {
				calls.add(ins);
			}
		}
		return calls;
	}

	// ---- code tables ----

	private boolean followCodeTables() throws Exception {
		boolean more = false;
		for (Instruction push : instructions()) {
			String m = push.getMnemonicString().toUpperCase();
			if (!m.equals("PUSHW") && !m.equals("PUSHA")) {
				continue;
			}
			String op = operand(push, 0);
			Matcher imm = IMMEDIATE.matcher(op);
			if (imm.matches()) {
				Address code = codeAddress(push, Long.parseLong(imm.group(1), 16));
				if (startCode(code)) {
					println("pushed code address " + code + " (at " + push.getAddress() + ")");
					more = true;
				}
				continue;
			}
			Long table = tableAddress(push, op);
			if (table != null && readTables.add(table)) {
				more |= readTable(push, table);
			}
		}
		return more;
	}

	/** Address (with bank) of the code table that a PUSHW operand reads, or null. */
	private Long tableAddress(Instruction push, String op) {
		Matcher indexed = INDEXED.matcher(op);
		if (indexed.matches()) {
			// An offset of 0xff00 or more is a small negative offset (as in [Rx+ff80]),
			// not the address of a table.
			long base = Long.parseLong(indexed.group(1), 16);
			return base >= ROM_START && base < 0xff00 ? base + dataBankOffset(push) : null;
		}
		Matcher indirect = INDIRECT.matcher(op);
		if (!indirect.matches()) {
			return null;
		}
		long inner = registerOffset(indirect.group(1));
		if (inner < 0) {
			return null;
		}
		if (inner >= 0x400) {
			return inner >= ROM_START ? inner : null;  // absolute address, bank included
		}
		Long base = baseLoadedInto(push, inner);
		return base != null && base >= ROM_START ? base + dataBankOffset(push) : null;
	}

	/** Look back in the block for LDW, AD2W or AD3W that writes a constant to the register. */
	private Long baseLoadedInto(Instruction push, long register) {
		Instruction cur = push;
		for (int i = 0; i < 8; i++) {
			Instruction prev = listing.getInstructionBefore(cur.getAddress());
			if (prev == null || !cur.getAddress().equals(prev.getFallThrough())) {
				return null;
			}
			if (prev.getNumOperands() > 0 && registerOffset(operand(prev, 0)) == register) {
				String m = prev.getMnemonicString().toUpperCase();
				int src = m.equals("AD3W") ? 2 : 1;
				Matcher imm = IMMEDIATE.matcher(operand(prev, src));
				boolean ok = (m.equals("LDW") || m.equals("AD2W") || m.equals("AD3W")) && imm.matches();
				return ok ? Long.valueOf(Long.parseLong(imm.group(1), 16)) : null;
			}
			cur = prev;
		}
		return null;
	}

	private boolean readTable(Instruction push, long table) throws Exception {
		Address entry = toAddr(table);
		int count = 0;
		for (int i = 0; i < MAX_TABLE_ENTRIES; i++, entry = entry.add(2)) {
			if (!currentProgram.getMemory().contains(new AddressSet(entry, entry.add(1)))) {
				break;
			}
			int word = currentProgram.getMemory().getShort(entry) & 0xffff;
			Address code = codeAddress(push, word);
			if (word < ROM_START || word == 0xffff || isErased(code) || !decodesAt(code)) {
				break;    // not a code address; 0xffff and ff ff are unused (erased) memory
			}
			currentProgram.getReferenceManager().addMemoryReference(entry, code,
				RefType.DATA, SourceType.ANALYSIS, 0);
			foundStarts.add(code);
			count++;
		}
		println(String.format("table %s (used at %s): %d code addresses", toAddr(table),
			push.getAddress(), count));
		if (count > 0 && getSymbolAt(toAddr(table)) == null) {
			createLabel(toAddr(table), String.format("CODE_TABLE_%x", table), true,
				SourceType.ANALYSIS);
		}
		return count > 0;
	}

	/** True if the address and the next byte are ff, the value of unused (erased) memory. */
	private boolean isErased(Address a) {
		try {
			return (getByte(a) & 0xff) == 0xff && (getByte(a.add(1)) & 0xff) == 0xff;
		}
		catch (Exception e) {
			return true;
		}
	}

	/** Disassemble at the address if needed; false if it is not code. */
	private boolean decodesAt(Address code) {
		if (!currentProgram.getMemory().contains(code)) {
			return false;
		}
		Instruction at = listing.getInstructionContaining(code);
		if (at != null) {
			return at.getAddress().equals(code);
		}
		disassembleAt(code);
		if (listing.getInstructionAt(code) != null) {
			return true;
		}
		removeErrorBookmarks(code);
		return false;
	}

	private boolean startCode(Address code) {
		if (!currentProgram.getMemory().contains(code) || listing.getInstructionAt(code) != null ||
			isErased(code)) {
			return false;
		}
		disassembleAt(code);
		boolean ok = listing.getInstructionAt(code) != null;
		if (ok) {
			foundStarts.add(code);
		}
		return ok;
	}

	// ---- code gaps ----

	private boolean scanCodeGaps() throws Exception {
		boolean more = false;
		dataConstants = constantAddresses();
		for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
			if (block.getStart().getOffset() < ROM_START || !block.isInitialized()) {
				continue;
			}
			AddressSet blockSet = new AddressSet(block.getStart(), block.getEnd());
			for (AddressRange gap : listing.getUndefinedRanges(blockSet, true, monitor)) {
				more |= scanGap(gap);
			}
		}
		return more;
	}

	private boolean scanGap(AddressRange gap) throws Exception {
		Address start = gap.getMinAddress();
		Address end = gap.getMaxAddress();
		if (!triedGaps.add(start.toString() + "-" + end)) {
			return false;    // tried before with the same bounds
		}
		Address before = start.previous();
		Address after = end.next();
		if (before == null || after == null || listing.getInstructionContaining(before) == null ||
			listing.getInstructionAt(after) == null) {
			return false;
		}
		int last = getByte(end) & 0xff;
		if ((last != 0xf0 && last != 0xf1) || referredToAsData(gap)) {
			return false;
		}
		if (!decodeWithoutErrors(start, end)) {
			return false;
		}
		foundStarts.add(start);
		println("code gap " + start + "-" + end);
		return true;
	}

	/**
	 * Disassemble from the start of a gap. Undo it and return false if it gives a decode error
	 * or if the last instruction it decodes in the gap is not a return or a jump.
	 */
	private boolean decodeWithoutErrors(Address start, Address end) throws Exception {
		Set<Address> errorsBefore = errorAddresses();
		DisassembleCommand cmd = new DisassembleCommand(start, null, true);
		cmd.applyTo(currentProgram, monitor);
		Set<Address> errorsAfter = errorAddresses();
		errorsAfter.removeAll(errorsBefore);
		Instruction last = listing.getInstructionContaining(end);
		if (last == null || last.getAddress().compareTo(start) < 0) {
			last = listing.getInstructionBefore(end.next());
		}
		boolean endsBlock = last != null && last.getAddress().compareTo(start) >= 0 &&
			!last.getFlowType().isFallthrough() && !last.getFlowType().isConditional();
		if (listing.getInstructionAt(start) != null && errorsAfter.isEmpty() && endsBlock) {
			return true;
		}
		println(String.format("code gap %s-%s not used: %s", start, end,
			errorsAfter.isEmpty() ? "the code does not end with a return or a jump"
					: "decode error at " + errorsAfter));
		AddressSet made = new AddressSet(cmd.getDisassembledAddressSet());
		clearListing(made, true, false, false, false, false, true, false, false, true, false,
			true, false);
		for (Address a : errorsAfter) {
			removeErrorBookmarks(a);
		}
		return false;
	}

	private Set<Address> errorAddresses() {
		Set<Address> errors = new LinkedHashSet<>();
		var it = currentProgram.getBookmarkManager().getBookmarksIterator(BookmarkType.ERROR);
		while (it.hasNext()) {
			errors.add(it.next().getAddress());
		}
		return errors;
	}

	/** Constant operands that are ROM addresses, from instructions other than PUSHW. */
	private Set<Long> constantAddresses() {
		Set<Long> constants = new LinkedHashSet<>();
		for (Instruction ins : instructions()) {
			if (ins.getMnemonicString().toUpperCase().startsWith("PUSH")) {
				continue;
			}
			for (int i = 0; i < ins.getNumOperands(); i++) {
				Matcher imm = IMMEDIATE.matcher(operand(ins, i));
				long value = imm.matches() ? Long.parseLong(imm.group(1), 16) : -1;
				if (value >= ROM_START) {
					constants.add((ins.getAddress().getOffset() & ~0xffffL) + value);
				}
			}
		}
		return constants;
	}

	private boolean referredToAsData(AddressRange gap) {
		if (dataConstants.contains(gap.getMinAddress().getOffset())) {
			return true;
		}
		for (Address a = gap.getMinAddress(); a.compareTo(gap.getMaxAddress()) <= 0; a = a.next()) {
			for (Reference ref : getReferencesTo(a)) {
				if (ref.getReferenceType().isData()) {
					return true;
				}
			}
		}
		return false;
	}

	// ---- skipped bytes ----

	private void decodeSkippedBytes() throws Exception {
		for (Instruction skip : instructions()) {
			if (!skip.getMnemonicString().equalsIgnoreCase("SKIP")) {
				continue;
			}
			Address next = skip.getMaxAddress().next();
			if (next == null || listing.getCodeUnitContaining(next) instanceof Instruction ||
				listing.getDefinedDataContaining(next) != null) {
				continue;
			}
			new DisassembleCommand(next, new AddressSet(next), false).applyTo(currentProgram,
				monitor);
			Instruction one = listing.getInstructionAt(next);
			if (one != null && one.getLength() != 1) {
				clearListing(new AddressSet(next, one.getMaxAddress()));
			}
			removeErrorBookmarks(next);
		}
	}

	// ---- helpers ----

	private void restart() throws Exception {
		restarts++;
		readTables.clear();
		triedGaps.clear();
		AddressSet rom = new AddressSet();
		for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
			if (block.getStart().getOffset() >= ROM_START) {
				rom.add(block.getStart(), block.getEnd());
			}
		}
		clearListing(rom, true, false, false, false, false, true, false, false, true, false,
			true, true);
		AddressIterator entries = currentProgram.getSymbolTable().getExternalEntryPointIterator();
		while (entries.hasNext()) {
			disassembleAt(entries.next());
		}
		for (Address start : foundStarts) {
			disassembleAt(start);
		}
		// The disassembler does not enter a function marked as not returning, so start at the
		// inline-argument subroutines themselves.
		for (Address entry : argumentBytes.keySet()) {
			disassembleAt(entry);
		}
	}

	private void disassembleAt(Address start) {
		if (currentProgram.getMemory().contains(start) && listing.getInstructionAt(start) == null) {
			new DisassembleCommand(start, null, true).applyTo(currentProgram, monitor);
		}
	}

	private void removeErrorBookmarks(Address a) {
		for (Bookmark b : currentProgram.getBookmarkManager().getBookmarks(a)) {
			if (BookmarkType.ERROR.equals(b.getTypeString())) {
				currentProgram.getBookmarkManager().removeBookmark(b);
			}
		}
	}

	private List<Instruction> instructions() {
		List<Instruction> all = new ArrayList<>();
		for (Instruction ins : listing.getInstructions(true)) {
			all.add(ins);
		}
		return all;
	}

	private Instruction fallThroughInstruction(Instruction ins) {
		Address next = ins.getFallThrough();
		return next == null ? null : listing.getInstructionAt(next);
	}

	private String operand(Instruction ins, int i) {
		return i < ins.getNumOperands() ? ins.getDefaultOperandRepresentation(i).trim() : "";
	}

	/** RAM offset of a register operand (0x00002c or a register name), or -1. */
	private long registerOffset(String text) {
		Matcher hex = HEX.matcher(text.toLowerCase());
		if (hex.matches()) {
			return Long.parseLong(hex.group(1), 16);
		}
		Register r = currentProgram.getRegister(text);
		return r != null && r.getAddress().isMemoryAddress() ? r.getAddress().getOffset() : -1;
	}

	/** A 16-bit code address in the code bank of the instruction. */
	private Address codeAddress(Instruction ins, long address16) {
		long bank = ins.getAddress().getOffset() & ~0xffffL;
		return toAddr(bank + (address16 & 0xffff));
	}

	/** Bank offset (bank * 0x10000) of the data address of an instruction. */
	private long dataBankOffset(Instruction ins) {
		Register on = currentProgram.getRegister("rombk_on");
		Register prefixBank = currentProgram.getRegister("rombk_bank");
		Register dataBank = currentProgram.getRegister("dbank");
		if (on == null || prefixBank == null || dataBank == null) {
			return 0;
		}
		BigInteger prefixed = ins.getValue(on, false);
		Register r = prefixed != null && prefixed.intValue() == 1 ? prefixBank : dataBank;
		BigInteger bank = ins.getValue(r, false);
		return bank == null ? 0 : bank.longValue() << 16;
	}
}
