// Loads the ROM banks of an EEC-IV image into the current program and disassembles the code
// from the reset entry and the interrupt vectors in bank 8.
//
// How to use it in the Ghidra GUI:
//   1. File > Import File, choose the image. Format: Raw Binary. Language: 8065:LE:24:default.
//      Options: Base Address 0x82000, File Offset 0x22000, Length 0xe000 (this loads bank 8
//      of a 256 KiB image). Do not run the auto-analysis yet.
//   2. Run this script (Script Manager, category EEC). It asks for the image file again,
//      adds the other banks and disassembles from the reset entry and the vectors. The reset
//      entry and the vector targets become entry points.
//   3. Optional: run EecFindCode.java to find more code (code tables, inline arguments).
//   4. Run the auto-analysis if you want it.
//
// With the 16-bit 8061 language (806x:LE:16:default) the script only disassembles bank 8,
// which must be loaded at 0x2000.
//
// Addresses in the 8065 language are bank * 0x10000 + 16-bit address. The image is split in
// 64 KiB blocks; the bytes of each bank start at offset 0x2000 in its block and are loaded at
// bank * 0x10000 + 0x2000.
//
// Arguments for analyzeHeadless (key=value):
//   image=<path>      the image file
//   order=0,1,8,9     bank number of each 64 KiB block of the file, in file order
//                     (default for a 256 KiB file: 0,1,8,9)
//   vectors=2010-205f interrupt vector table in bank 8, inclusive (default: the 40 vectors
//                     of the 8065)
//@category EEC

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;

public class EecLoadBanks extends GhidraScript {

	static final int BANK_SIZE = 0x10000;
	static final int ROM_START = 0x2000;
	static final int ROM_LENGTH = BANK_SIZE - ROM_START;
	static final int CODE_BANK = 8;

	private AddressSpace space;
	private boolean banked;

	@Override
	protected void run() throws Exception {
		Map<String, String> args = parseArgs(getScriptArgs());
		space = currentProgram.getAddressFactory().getDefaultAddressSpace();
		banked = space.getSize() > 16;
		if (banked) {
			byte[] image = Files.readAllBytes(imageFile(args).toPath());
			loadBanks(image, bankOrder(args.get("order"), image.length));
		}
		int[] vectors = parseRange(args.getOrDefault("vectors", "2010-205f"));
		disassembleFromEntries(vectors);
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

	private File imageFile(Map<String, String> args) throws Exception {
		if (args.containsKey("image")) {
			return new File(args.get("image"));
		}
		return askFile("EEC-IV image with all banks", "Load banks");
	}

	/** Bank number of each 64 KiB block of the file. */
	private int[] bankOrder(String text, int fileSize) {
		if (text == null) {
			if (fileSize != 4 * BANK_SIZE) {
				throw new IllegalArgumentException("give order=... for a file of " + fileSize +
					" bytes; the default order 0,1,8,9 is for 262144-byte files");
			}
			return new int[] { 0, 1, 8, 9 };
		}
		String[] parts = text.split(",");
		int[] order = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			order[i] = Integer.parseInt(parts[i].trim());
		}
		if (order.length * BANK_SIZE > fileSize) {
			throw new IllegalArgumentException("order has more blocks than the file");
		}
		return order;
	}

	private void loadBanks(byte[] image, int[] order) throws Exception {
		Memory memory = currentProgram.getMemory();
		for (int slot = 0; slot < order.length; slot++) {
			Address start = space.getAddress((long) order[slot] * BANK_SIZE + ROM_START);
			Address end = start.add(ROM_LENGTH - 1);
			if (memory.intersects(start, end)) {
				println("bank " + order[slot] + " is already in memory; not loaded again");
				continue;
			}
			ByteArrayInputStream bytes =
				new ByteArrayInputStream(image, slot * BANK_SIZE + ROM_START, ROM_LENGTH);
			MemoryBlock block = memory.createInitializedBlock("bank" + order[slot], start, bytes,
				ROM_LENGTH, monitor, false);
			block.setPermissions(true, false, true);
			println("loaded bank " + order[slot] + " at " + start);
		}
	}

	private int[] parseRange(String text) {
		String[] parts = text.split("-");
		if (parts.length != 2) {
			throw new IllegalArgumentException("range must be <first>-<last>: " + text);
		}
		return new int[] { Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16) };
	}

	/** Address of a 16-bit address in the code bank (bank 8). */
	private Address codeAddress(int address16) {
		return space.getAddress(banked ? (long) CODE_BANK * BANK_SIZE + address16 : address16);
	}

	/** Disassemble from the reset entry and the vector targets, and mark them as entry points. */
	private void disassembleFromEntries(int[] vectors) throws Exception {
		disassembleAt(codeAddress(ROM_START));
		addEntryIfLoaded(codeAddress(ROM_START));
		Memory memory = currentProgram.getMemory();
		for (int v = vectors[0]; v + 1 <= vectors[1]; v += 2) {
			Address vector = codeAddress(v);
			if (!memory.contains(vector)) {
				continue;
			}
			int target = memory.getShort(vector) & 0xffff;
			if (target < ROM_START) {
				continue;
			}
			Address code = codeAddress(target);
			disassembleAt(code);
			addEntryIfLoaded(code);
			if (getSymbolAt(code) == null) {
				createLabel(code, String.format("INT_%04x", v), true, SourceType.ANALYSIS);
			}
		}
	}

	private void addEntryIfLoaded(Address a) {
		if (currentProgram.getMemory().contains(a)) {
			currentProgram.getSymbolTable().addExternalEntryPoint(a);
		}
	}

	private void disassembleAt(Address start) {
		if (!currentProgram.getMemory().contains(start) ||
			currentProgram.getListing().getInstructionAt(start) != null) {
			return;
		}
		new DisassembleCommand(start, null, true).applyTo(currentProgram, monitor);
	}
}
