package io.github.anoncodedrop;

import com.microsoft.z3.Status;
import io.github.alisianoi.expert.model.WViewFunction;
import io.github.alisianoi.expert.wexpert.WEFunction;
import io.github.alisianoi.expert.wexpert.WEImport;
import io.github.alisianoi.parser.model.WModule;
import io.github.alisianoi.parser.model.section.WSectionCode;
import io.github.alisianoi.parser.model.section.WSectionFunction;
import io.github.alisianoi.parser.model.section.WSectionImport;
import io.github.alisianoi.parser.model.section.WSectionType;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public class TestCompTestsSelectorFunctionProvider {
    private final List<BigInteger> reachErrorFunctionId;
    private final BigInteger mainFunctionId;
    private final Optional<Status> expectedResult;

    public static TestCompTestsSelectorFunctionProvider forCallUnreachable(WModule module, String value) {
        return new TestCompTestsSelectorFunctionProvider(module, value, true);
    }

    public static TestCompTestsSelectorFunctionProvider get(WModule module, String value) {
        return new TestCompTestsSelectorFunctionProvider(module, value, false);
    }

    private TestCompTestsSelectorFunctionProvider(WModule module, String value, boolean callUnreachable) {
        mainFunctionId = getFunctionExportedIdByName(module, "main");
        if (callUnreachable) {
            reachErrorFunctionId = List.of(getFunctionImportedIdByName(module, "reach_error"));
            expectedResult = switch (value) {
                case "SAT" -> Optional.of(Status.SATISFIABLE);
                case "UNSAT" -> Optional.of(Status.UNSATISFIABLE);
                default -> throw new IllegalArgumentException("Result has to be 'SAT' or 'UNSAT' but was '" + value + "'");
            };
        } else {
            reachErrorFunctionId = List.of();
            expectedResult = Optional.empty();
        }

        WViewFunction viewFunction = new WViewFunction();
        Optional<WSectionType> sectionType = module.findSectionType();
        Optional<WSectionImport> sectionImport = module.findSectionImport();
        Optional<WSectionCode> sectionCode = module.findSectionCode();
        Optional<WSectionFunction> sectionFunction = module.findSectionFunction();

        if (sectionType.isPresent() && sectionImport.isPresent()) {
            WEImport.map(viewFunction, sectionType.get(), sectionImport.get());
        }

        if (sectionType.isPresent() && sectionCode.isPresent() && sectionFunction.isPresent()) {
            WEFunction.map(viewFunction, sectionType.get(), sectionCode.get(), sectionFunction.get());
        }
    }

    public Iterable<Boolean> callActuallyUnreachable() {
        return () -> expectedResult.filter(s -> s == Status.UNSATISFIABLE).map(s -> true).stream().iterator();
    }

    public Iterable<Boolean> callNotActuallyUnreachable() {
        return () -> expectedResult.filter(s -> s == Status.SATISFIABLE).map(s -> true).stream().iterator();
    }

    private BigInteger getFunctionImportedIdByName(WModule module, String name) {
        Optional<WSectionImport.Entry> relevantEntry = module.findSectionImport().stream()
                .flatMap(e -> e.getEntries().stream())
                .filter(e -> e.getName().equals(name)).findAny();

        return BigInteger.valueOf(module.findSectionImport().get().getEntries().indexOf(relevantEntry.get()));
    }

    private BigInteger getFunctionExportedIdByName(WModule module, String name) {
        return BigInteger.valueOf(module.findSectionExport().stream()
                .flatMap(e -> e.getEntries().stream())
                .filter(e -> e.getName().equals(name)).findAny()
                .orElseThrow(() -> new IllegalArgumentException("The provided function does not contain an exported function '" + name + "'!")).getValue());
    }

    public Iterable<BigInteger> startFunctionId() {
        return List.of(mainFunctionId);
    }

    public Iterable<BigInteger> unreachableFunctionId() {
        return reachErrorFunctionId;
    }
}
