// Static targeted probe for a large user-supplied PE. No application execution.
// Run with Ghidra headless -readOnly -noanalysis -deleteProject.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class NativeEntryProbe extends GhidraScript {
    private Map<String, Object> item(Object... fields) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]);
        return value;
    }
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) throw new IllegalArgumentException("Output path and PE entry address required");
        Map<String, Object> report = item("tool", "Ghidra targeted static probe",
            "version", "1", "analysisMode", "noanalysis then selected function disassembly",
            "input", currentProgram.getExecutablePath(),
            "sha256", currentProgram.getExecutableSHA256(),
            "format", currentProgram.getExecutableFormat(),
            "language", currentProgram.getLanguageID().toString(),
            "imageBase", currentProgram.getImageBase().toString(),
            "targetExecuted", false, "encryptedJavaScriptRecovered", false);
        List<Object> blocks = new ArrayList<>();
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            blocks.add(item("name", block.getName(), "start", block.getStart().toString(),
                "end", block.getEnd().toString(), "bytes", block.getSize(),
                "read", block.isRead(), "write", block.isWrite(), "execute", block.isExecute()));
        }
        report.put("memoryBlocks", blocks);
        List<Object> imports = new ArrayList<>();
        for (Symbol symbol : currentProgram.getSymbolTable().getAllSymbols(true)) {
            if (symbol.isExternal() && imports.size() < 2048) {
                imports.add(item("name", symbol.getName(), "namespace",
                    symbol.getParentNamespace().getName(true), "address", symbol.getAddress().toString()));
            }
        }
        report.put("imports", imports);
        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        List<Address> queue = new ArrayList<>();
        for (String seed : args[1].split(",")) queue.add(
            currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(Long.decode(seed)));
        Set<Address> visited = new LinkedHashSet<>();
        List<Object> functions = new ArrayList<>();
        try {
            for (int cursor = 0; cursor < queue.size() && functions.size() < 8; cursor++) {
                monitor.checkCancelled();
                Address address = queue.get(cursor);
                if (!visited.add(address)) continue;
                MemoryBlock block = currentProgram.getMemory().getBlock(address);
                if (block == null || !block.isExecute()) continue;
                disassemble(address);
                Function function = getFunctionAt(address);
                if (function == null) function = createFunction(address, null);
                if (function == null) continue;
                // The PE importer gives some entry symbols a one-byte body.
                // Walk decoded intraprocedural flows before collecting calls;
                // ordinary full auto-analysis is intentionally not enabled.
                AddressSet body = new AddressSet();
                Set<Address> decoded = new LinkedHashSet<>();
                List<Address> flow = new ArrayList<>();
                flow.add(address);
                for (int next = 0; next < flow.size() && decoded.size() < 2500; next++) {
                    Address pc = flow.get(next);
                    if (!decoded.add(pc)) continue;
                    Instruction instruction = getInstructionAt(pc);
                    if (instruction == null) continue;
                    body.add(instruction.getMinAddress(), instruction.getMaxAddress());
                    Address fallthrough = instruction.getFallThrough();
                    if (fallthrough != null) flow.add(fallthrough);
                    if (!instruction.getFlowType().isCall()) {
                        for (Address target : instruction.getFlows()) {
                            MemoryBlock targetBlock = currentProgram.getMemory().getBlock(target);
                            if (targetBlock != null && targetBlock.isExecute()) flow.add(target);
                        }
                    }
                }
                try {function.setBody(body);} catch (Exception conflict) {}
                List<Object> calls = new ArrayList<>();
                List<String> assembly = new ArrayList<>();
                int instructions = 0;
                for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
                    if (++instructions > 2500) break;
                    assembly.add(instruction.getAddress() + ": " + instruction);
                    for (Reference reference : instruction.getReferencesFrom()) {
                        if (!reference.getReferenceType().isCall()) continue;
                        calls.add(item("from", reference.getFromAddress().toString(),
                            "to", reference.getToAddress().toString(),
                            "indirect", reference.getReferenceType().isComputed()));
                        if (queue.size() < 24 && !reference.getReferenceType().isComputed())
                            queue.add(reference.getToAddress());
                    }
                }
                DecompileResults result = decompiler.decompileFunction(function, 30, monitor);
                String code = result.decompileCompleted() && result.getDecompiledFunction() != null
                    ? result.getDecompiledFunction().getC() : null;
                functions.add(item("entry", address.toString(), "name", function.getName(),
                    "bodyBytes", function.getBody().getNumAddresses(), "instructionCount", instructions,
                    "boundedFlowLimitReached", decoded.size() >= 2500,
                    "assembly", assembly, "calls", calls, "pseudocode", code,
                    "decompilerError", result.getErrorMessage()));
            }
        } finally {
            decompiler.dispose();
        }
        report.put("functions", functions);
        report.put("limitations", List.of(
            "No full-program auto-analysis; only up to eight reachable entry functions inspected.",
            "Decompiler pseudocode is inferred machine-code behavior, not recovered original source.",
            "Indirect calls and encrypted bytecode remain unresolved.",
            "This probe does not modify or execute the supplied application."));
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        println("Targeted static probe wrote " + functions.size() + " functions.");
    }
}
