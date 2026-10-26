// Writes the instruction listing of the current program in the comparison format, after
// optionally starting disassembly at every code address of a reference listing.
//
// test/run-compare.sh runs this script through analyzeHeadless as a post-script, after
// ghidra_scripts/EecLoadBanks.java has loaded the banks and disassembled from the reset
// entry and the interrupt vectors. The script takes key=value arguments:
//
//   out=<path>           The listing to write.
//   seeds=<path>         Optional. A listing in the comparison format. The script also
//                        starts disassembly at the address of every row whose kind is "code".
//   seedflow=true|false  With seeds: true (default) follows jumps and calls from each seed;
//                        false decodes one instruction at each seed only, which tests the
//                        decoding without flow analysis.
//
// The listing is tab-separated with the header
//   addr  kind  len  bytes  mnemonic  operands
// addr is lowercase hex without 0x: 4 digits for 0x0000-0x1fff and for programs with a
// 16-bit address space, 5 digits (bank * 0x10000 + 16-bit address) for banked addresses.
// Instructions have kind "code". Addresses where Ghidra could not decode an instruction
// (error bookmarks) have kind "other" and mnemonic "!inv!".
//@category EEC

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.listing.Bookmark;
import ghidra.program.model.listing.BookmarkType;
import ghidra.program.model.listing.Instruction;

public class EecListing extends GhidraScript {

	private static final int BANK_SIZE = 0x10000;
	private static final int ROM_START = 0x2000;
	private static final int CODE_BANK = 8;

	private boolean banked;
	private AddressSpace space;

	@Override
	protected void run() throws Exception {
		Map<String, String> args = parseArgs(getScriptArgs());
		space = currentProgram.getAddressFactory().getDefaultAddressSpace();
		banked = space.getSize() > 16;
		if (args.containsKey("seeds")) {
			boolean follow = !"false".equals(args.getOrDefault("seedflow", "true"));
			disassembleSeeds(Path.of(args.get("seeds")), follow);
		}
		writeListing(Path.of(require(args, "out")));
	}

	private Map<String, String> parseArgs(String[] raw) {
		Map<String, String> args = new HashMap<>();
		for (String arg : raw) {
			int eq = arg.indexOf('=');
			if (eq <= 0) {
				throw new IllegalArgumentException("argument is not key=value: " + arg);
			}
			args.put(arg.substring(0, eq), arg.substring(eq + 1));
		}
		return args;
	}

	private String require(Map<String, String> args, String key) {
		String value = args.get(key);
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("missing argument " + key + "=...");
		}
		return value;
	}

	private void disassemble(Address start, boolean follow) {
		if (!currentProgram.getMemory().contains(start) ||
			currentProgram.getListing().getInstructionAt(start) != null) {
			return;
		}
		DisassembleCommand cmd =
			new DisassembleCommand(start, follow ? null : new AddressSet(start), follow);
		cmd.applyTo(currentProgram, monitor);
	}

	/** Start disassembly at every code row of a reference listing, in address order. */
	private void disassembleSeeds(Path seedsPath, boolean follow) throws IOException {
		TreeSet<Long> seeds = new TreeSet<>();
		List<String> lines = Files.readAllLines(seedsPath, StandardCharsets.UTF_8);
		for (String line : lines.subList(1, lines.size())) {
			String[] cols = line.split("\t", -1);
			if (cols.length < 2 || !"code".equals(cols[1])) {
				continue;
			}
			Long offset = seedOffset(cols[0]);
			if (offset != null) {
				seeds.add(offset);
			}
		}
		for (long offset : seeds) {
			disassemble(space.getAddress(offset), follow);
		}
		println("seeded disassembly from " + seeds.size() + " reference code addresses" +
			(follow ? "" : ", without following flow"));
	}

	/** Map a reference address to this program's address, or null if it is not loaded. */
	private Long seedOffset(String text) {
		long value = Long.parseLong(text, 16);
		boolean bankedText = text.length() == 5;
		if (banked) {
			return bankedText || value < ROM_START ? value : CODE_BANK * BANK_SIZE + value;
		}
		if (!bankedText) {
			return value;
		}
		return (value >> 16) == CODE_BANK ? value & 0xffff : null;
	}

	private void writeListing(Path out) throws IOException {
		TreeMap<Long, String> rows = new TreeMap<>();
		for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
			rows.put(ins.getAddress().getOffset(), instructionRow(ins));
		}
		int errors = 0;
		Iterator<Bookmark> it =
			currentProgram.getBookmarkManager().getBookmarksIterator(BookmarkType.ERROR);
		while (it.hasNext()) {
			Bookmark bookmark = it.next();
			long offset = bookmark.getAddress().getOffset();
			if (!rows.containsKey(offset)) {
				rows.put(offset, errorRow(bookmark));
				errors++;
			}
		}
		Files.createDirectories(out.toAbsolutePath().getParent());
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
			w.print("addr\tkind\tlen\tbytes\tmnemonic\toperands\n");
			for (String row : rows.values()) {
				w.print(row);
				w.print('\n');
			}
		}
		println(String.format("wrote %d rows (%d instructions, %d decode errors) to %s",
			rows.size(), rows.size() - errors, errors, out));
	}

	private String instructionRow(Instruction ins) throws IOException {
		String text = ins.toString();
		String mnemonic = ins.getMnemonicString();
		String operands = text.startsWith(mnemonic) ? text.substring(mnemonic.length()).trim() : text;
		byte[] bytes;
		try {
			bytes = ins.getBytes();
		}
		catch (Exception e) {
			throw new IOException("cannot read bytes at " + ins.getAddress(), e);
		}
		return String.join("\t", formatAddr(ins.getAddress().getOffset()), "code",
			Integer.toString(ins.getLength()), hex(bytes), mnemonic.toLowerCase(), clean(operands));
	}

	private String errorRow(Bookmark bookmark) {
		Address a = bookmark.getAddress();
		String byteText = "";
		try {
			byteText = hex(new byte[] { currentProgram.getMemory().getByte(a) });
		}
		catch (Exception e) {
			// address not in memory: leave the bytes column empty
		}
		return String.join("\t", formatAddr(a.getOffset()), "other", byteText.isEmpty() ? "0" : "1",
			byteText, "!inv!", clean(bookmark.getComment()));
	}

	private String formatAddr(long offset) {
		if (banked && offset >= ROM_START) {
			return String.format("%05x", offset);
		}
		return String.format("%04x", offset);
	}

	private static String hex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b & 0xff));
		}
		return sb.toString();
	}

	private static String clean(String text) {
		return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
