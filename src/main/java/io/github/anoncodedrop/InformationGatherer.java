package io.github.anoncodedrop;

import io.github.alisianoi.WParser;
import io.github.alisianoi.expert.model.WViewFunction;
import io.github.alisianoi.expert.model.api.WIFunction;
import io.github.alisianoi.expert.wexpert.WEFunction;
import io.github.alisianoi.expert.wexpert.WEImport;
import io.github.alisianoi.parser.model.instruction.WCode;
import io.github.alisianoi.parser.model.section.*;
import io.github.alisianoi.parser.model.type.WValueType;
import picocli.CommandLine;
import wien.secpriv.horst.data.tuples.Tuple3;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@CommandLine.Command(name = "InformationGatherer", mixinStandardHelpOptions = true)
public class InformationGatherer implements Runnable {

    @CommandLine.Parameters
    private String[] args;
    private WViewFunction viewFunction;
    private List<BigInteger> globalBitWidth = new ArrayList<>();
    private Optional<Tuple3<Boolean, BigInteger, BigInteger>> memory;

    public static void main(String[] args) {
        CommandLine.run(new InformationGatherer(), args);
    }

    @Override
    public void run() {
        var path = args[0];
        var module = new WParser().parse(path);

        Optional<WSectionType> sectionType = module.findSectionType();
        Optional<WSectionImport> sectionImport = module.findSectionImport();
        Optional<WSectionMemory> sectionMemory = module.findSectionMemory();
        Optional<WSectionCode> sectionCode = module.findSectionCode();
        Optional<WSectionFunction> sectionFunction = module.findSectionFunction();

        this.viewFunction = new WViewFunction();

        if (sectionType.isPresent() && sectionImport.isPresent()) {
            WEImport.map(this.viewFunction, sectionType.get(), sectionImport.get());
        }

        if (sectionType.isPresent() && sectionCode.isPresent() && sectionFunction.isPresent()) {
            WEFunction.map(this.viewFunction, sectionType.get(), sectionCode.get(), sectionFunction.get());
        }

        boolean global64 = false;
        if (module.findSectionGlobal().isPresent()) {
            global64 = module.findSectionGlobal().get().getEntries().stream().anyMatch(type -> is64BitType(type.getValueType()));
        }

        boolean signature64 = viewFunction.getFunctions().stream().anyMatch(InformationGatherer::contains64BitInstruction);

        boolean instructions64 = sectionCode.map(sc -> sc.getEntries().stream().anyMatch(this::contains64BitInstruction)).orElse(false);

        System.out.println("contains64: " + (global64 || signature64 || instructions64));
        System.out.println("loops: " + sectionCode.map(sc -> sc.getEntries().stream().anyMatch(this::containsLoopBitInstruction)).orElse(false));

        /*
        if (sectionMemory.isPresent()) {
            WSectionMemoryEntry mem = sectionMemory.get().getEntries().get(0);
            long maxLimit = Optional.ofNullable(mem.getMaxLimit()).orElse(65536L);
            memory = Optional.of(new Tuple3<>(false, BigInteger.valueOf(mem.getMinLimit()), BigInteger.valueOf(maxLimit)));
        }

        if (memory.isEmpty() && sectionImport.isPresent()) {
            for (WSectionImport.Entry e : sectionImport.get().getEntries()) {
                if (e instanceof WSectionImport.MemoryTypeEntry) {
                    WSectionImport.MemoryTypeEntry mem = (WSectionImport.MemoryTypeEntry) e;
                    long maxLimit = Optional.ofNullable(mem.getMaxLimit()).orElse(65536L);
                    memory = Optional.of(new Tuple3<>(true, BigInteger.valueOf(mem.getMinLimit()), BigInteger.valueOf(maxLimit)));
                }
            }
        }
         */
    }

    private boolean contains64BitInstruction(WSectionCodeEntry entry) {
        for(var instr : entry.getBody()) {
            if(is64BitInstruction(instr.getCode())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsLoopBitInstruction(WSectionCodeEntry entry) {
        for(var instr : entry.getBody()) {
            if(instr.getCode() == WCode.loop) {
                return true;
            }
        }
        return false;
    }

    private static boolean is64BitType(WValueType type) {
        return switch (type) {
            case F64, I64 -> true;
            default -> false;
        };
    }

    private static boolean is64BitInstruction(int code) {
        return WCode.asString(code).contains("64");
    }

    private static boolean contains64BitInstruction(WIFunction function) {
        if (function.getResTypes().stream().anyMatch(InformationGatherer::is64BitType)) {
            return true;
        }
        if (function.getArgTypes().stream().anyMatch(InformationGatherer::is64BitType)) {
            return true;
        }

        return false;//contains64BitInstruction(function.getBody()) ;
    }
}
