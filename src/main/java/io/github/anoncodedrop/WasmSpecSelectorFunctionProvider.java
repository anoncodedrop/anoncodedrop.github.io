package io.github.anoncodedrop;

import io.github.alisianoi.parser.model.WModule;
import io.github.alisianoi.parser.model.instruction.*;
import io.github.alisianoi.parser.model.section.WSectionCodeEntry;
import io.github.alisianoi.parser.model.section.WSectionExportEntry;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.io.DOTExporter;
import wien.secpriv.horst.data.tuples.Tuple2;
import wien.secpriv.horst.tools.OptionalIterator;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class WasmSpecSelectorFunctionProvider {
    private final List<Tuple2<Integer, List<Optional<BigInteger>>>> assertReturns = new ArrayList<>();
    private final List<Integer> assertTraps = new ArrayList<>();
    private final List<Tuple2<String, List<Optional<BigInteger>>>> invocations = new ArrayList<>();
    private final Map<String, Long> exportedFunctions;
    private final List<BigInteger> independentInvocations = new ArrayList<>();
    private final List<SortedSet<BigInteger>> dependentInvocations = new ArrayList<>();
    private final boolean executeIndependent;

    private static int graphCount = 0;

    private static class TokenIterator extends OptionalIterator<String> {
        private final BufferedReader reader;

        private enum State {IN_STRING, NOT_IN_STRING}

        private State state = State.NOT_IN_STRING;
        private String next = null;

        private TokenIterator(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public Optional<String> maybeNext() {
            if (next != null) {
                Optional<String> ret = Optional.of(next);
                next = null;
                return ret;
            }
            try {
                StringBuilder sb = new StringBuilder();

                while (reader.ready()) {
                    int c = reader.read();

                    switch (state) {
                        case NOT_IN_STRING:
                            switch (c) {
                                case -1:
                                    if (sb.isEmpty()) {
                                        return Optional.empty();
                                    }
                                    return Optional.of(sb.toString());
                                case ' ':
                                case '\n':
                                    if (!sb.toString().strip().isEmpty()) {
                                        return Optional.of(sb.toString());
                                    }
                                    break;
                                case '(':
                                case ')':
                                    if (sb.toString().strip().isEmpty()) {
                                        return Optional.of(Character.toString(c));
                                    } else {
                                        next = Character.toString(c);
                                        return Optional.of(sb.toString());
                                    }
                                case '"':
                                    state = State.IN_STRING;
                                    if (sb.toString().strip().isEmpty()) {
                                        sb.setLength(0);
                                        break;
                                    } else {
                                        return Optional.of(sb.toString());
                                    }
                                default:
                                    sb.append((char) c);
                            }
                            break;
                        case IN_STRING:
                            switch (c) {
                                case '"':
                                    state = State.NOT_IN_STRING;
                                    return Optional.of(sb.toString());
                                default:
                                    sb.append((char) c);
                            }
                    }
                }

                return Optional.empty();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class DataDependency {
        enum Kind {
            READS_MEMORY,
            WRITES_MEMORY,
            READS_GLOBAL,
            WRITES_GLOBAL;
        }

        public DataDependency getCorrespondingWrite() {
            return switch (kind) {
                case READS_GLOBAL -> writeGlobal(index);
                case READS_MEMORY -> writeMemory();
                default -> this;
            };
        }

        private DataDependency(Kind kind, int index) {
            this.kind = kind;
            this.index = index;
        }

        static DataDependency readGlobal(int i) {
            return new DataDependency(Kind.READS_GLOBAL, i);
        }

        static DataDependency writeGlobal(int i) {
            return new DataDependency(Kind.WRITES_GLOBAL, i);
        }

        static DataDependency readMemory() {
            return new DataDependency(Kind.READS_MEMORY, 0);
        }

        static DataDependency writeMemory() {
            return new DataDependency(Kind.WRITES_MEMORY, 0);
        }

        private final Kind kind;
        private final int index;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            DataDependency that = (DataDependency) o;

            if (index != that.index) return false;
            return kind == that.kind;
        }

        @Override
        public int hashCode() {
            int result = kind.hashCode();
            result = 31 * result + index;
            return result;
        }
    }

    public WasmSpecSelectorFunctionProvider(InputStream specPath, WModule module) {
        this(specPath, module, false);
    }

    public WasmSpecSelectorFunctionProvider(InputStream specPath, WModule module, boolean executeIndependent) {

        this.executeIndependent = executeIndependent;

        exportedFunctions = module.findSectionExport().stream()
                .flatMap(e -> e.getEntries().stream())
                .filter(e -> e.getType() == 0) //this means it is a function
                .collect(Collectors.toMap(
                        WSectionExportEntry::getName,
                        WSectionExportEntry::getValue
                ));

        var iter = new TokenIterator(new BufferedReader(new InputStreamReader(specPath)));
        while (iter.hasNext()) {
            parseTopLevel(iter);
        }

        int importedFunctionCount = Math.toIntExact(module.findSectionImport().stream()
                .flatMap(i -> i.getEntries().stream())
                .filter(e -> e.getCode() == 0) // this means its a function
                .count());

        List<List<WInstruction>> codes = module.findSectionCode().stream().flatMap(c -> c.getEntries().stream()).map(WSectionCodeEntry::getBody).collect(Collectors.toList());

        Map<Integer, Set<DataDependency>> dataDependencies = new HashMap<>();

        for (int i = 0; i < codes.size(); ++i) {
            //TODO this is unsound because we need to consider function calls (transitive call graph)
            Set<DataDependency> dependencies = new HashSet<>();
            calculateDataDependencies(codes.get(i), dependencies);
            dataDependencies.put(i + importedFunctionCount, dependencies);
        }

        Graph<Integer, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

        //NOTE this could be at least twice as fast with a if the edges where treated undirected, but I like the debug-output
        for (int i = 0; i < invocations.size(); ++i) {
            for (int j = 0; j < invocations.size(); ++j) {
                graph.addVertex(j);
                Set<DataDependency> id = dataDependencies.get(exportedFunctions.get(invocations.get(i).v0).intValue());
                Set<DataDependency> jd = dataDependencies.get(exportedFunctions.get(invocations.get(j).v0).intValue());

                if (hasRelevantFlow(jd, id)) {
                    graph.addEdge(j, i);
                }
            }
        }

        ConnectivityInspector<Integer, DefaultEdge> inspector = new ConnectivityInspector<>(graph);

        for (var connectedComponent : inspector.connectedSets()) {
            if (connectedComponent.size() == 1) {
                independentInvocations.add(BigInteger.valueOf(connectedComponent.stream().findAny().get()));
            } else {
                SortedSet<BigInteger> invocationChain = connectedComponent.stream().map(BigInteger::valueOf).collect(Collectors.toCollection(TreeSet::new));

                Set<BigInteger> trapInvocations = assertTraps.stream().map(BigInteger::valueOf).collect(Collectors.toSet());
                SortedSet<BigInteger> returningInvocations = invocationChain.stream().filter(i -> !trapInvocations.contains(i)).collect(Collectors.toCollection(TreeSet::new));

                Optional<BigInteger> optFirstReturning = Optional.empty();

                if (!returningInvocations.isEmpty()) {
                    optFirstReturning = Optional.ofNullable(returningInvocations.first());
                }

                if (optFirstReturning.isEmpty()) {
                    independentInvocations.addAll(invocationChain);
                } else {
                    BigInteger firstReturning = optFirstReturning.get();

                    independentInvocations.addAll(invocationChain.headSet(firstReturning));
                    independentInvocations.add(firstReturning);

                    dependentInvocations.add(invocationChain.tailSet(firstReturning));
                }
            }
        }

        try {
            DOTExporter<Integer, DefaultEdge> exporter = new DOTExporter<>();
            exporter.exportGraph(graph, new FileOutputStream("/tmp/graph" + (++graphCount)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasRelevantFlow(Set<DataDependency> v1, Set<DataDependency> v2) {
        for (DataDependency d1 : v1) {
            if (v2.contains(d1.getCorrespondingWrite())) {
                return true;
            }
        }
        return false;
    }

    private void calculateDataDependencies(List<WInstruction> instructions, Set<DataDependency> dependencies) {
        for (WInstruction instruction : instructions) {
            if (instruction instanceof WInstructionVariable instructionVariable) {
                if (instruction.getCode() == WCode.globalGet) {
                    dependencies.add(DataDependency.readGlobal(instructionVariable.getIndex()));
                }
                if (instruction.getCode() == WCode.globalSet) {
                    dependencies.add(DataDependency.writeGlobal(instructionVariable.getIndex()));
                }
            } else if (instruction instanceof WInstructionMemoryLoadOrStore) {
                if (instruction.toString().contains("load")) {
                    dependencies.add(DataDependency.readMemory());
                }
                if (instruction.toString().contains("store")) {
                    dependencies.add(DataDependency.writeMemory());
                }
            } else if (instruction instanceof WInstructionMemory) {
                if (instruction.toString().contains("size")) {
                    dependencies.add(DataDependency.readMemory());
                }
                if (instruction.toString().contains("grow")) {
                    dependencies.add(DataDependency.writeMemory());
                    dependencies.add(DataDependency.readMemory());
                }
            } else if (instruction instanceof WInstructionBlock block) {
                calculateDataDependencies(block.getBody(), dependencies);
            } else if (instruction instanceof WInstructionBlockIfElse block) {
                calculateDataDependencies(block.getBodyIf(), dependencies);
                calculateDataDependencies(block.getBodyElse(), dependencies);
            }
        }
    }

    private static void expect(TokenIterator iterator, String expected) {
        String next = iterator.next();
        if (!expected.equals(next)) {
            throwOnUnexpected(next, expected);
        }
    }

    private static void throwOnUnexpected(String got, String... expected) {
        String sb = "Expected " +
                Arrays.stream(expected).map(s -> "'" + s + "'").collect(Collectors.joining(", ")) +
                " but got '" + got + "'!";

        throw new IllegalArgumentException(sb);
    }

    private static BigInteger parseInteger(String stringValue, int bitWidth) {
        BigInteger intValue;
        if (stringValue.startsWith("0x")) {
            intValue = new BigInteger(stringValue.substring(2).replace("_", ""), 16);
        } else {
            intValue = new BigInteger(stringValue);
        }

        if (intValue.compareTo(BigInteger.ZERO) < 0) {
            return BigInteger.valueOf(2).pow(bitWidth).add(intValue);
        }
        return intValue;
    }

    private void parseConst(TokenIterator iter, Consumer<Optional<BigInteger>> consumer) {
        String type = iter.next();
        String value = iter.next();
        expect(iter, ")");


        switch (type) {
            case "i32.const" -> consumer.accept(Optional.of(parseInteger(value, 32)));
            case "i64.const" -> consumer.accept(Optional.of(parseInteger(value, 64)));
            case "f32.const", "f64.const" -> consumer.accept(Optional.empty());
            default -> throwOnUnexpected(type, "i32.const", "i64.const", "f32.const", "f64.const");
        }
    }

    private void parseInvoke(TokenIterator iter, BiConsumer<String, List<Optional<BigInteger>>> consumer) {
        String name = iter.next();

        List<Optional<BigInteger>> args = new ArrayList<>();

        while (true) {
            String s = iter.next();
            switch (s) {
                case ")" -> {
                    consumer.accept(name, args);
                    return;
                }
                case "(" -> parseConst(iter, args::add);
                default -> throwOnUnexpected(s, "(", ")");
            }
        }
    }

    private void parseAssertReturn(TokenIterator iter) {
        expect(iter, "(");
        expect(iter, "invoke");
        int invocationId = invocations.size();
        parseInvoke(iter, (n, a) -> invocations.add(new Tuple2<>(n, a)));
        String s = iter.next();

        switch (s) {
            case "(" -> {
                parseConst(iter, c -> {
                    assertReturns.add(new Tuple2<>(invocationId, List.of(c)));
                });
                expect(iter, ")");
            }
            case ")" -> assertReturns.add(new Tuple2<>(invocationId, List.of()));
            default -> throwOnUnexpected(s, "(", ")");
        }
    }

    private void parseAssertTrap(TokenIterator iter) {
        expect(iter, "(");
        expect(iter, "invoke");
        parseInvoke(iter, (n, a) -> {
            assertTraps.add(invocations.size());
            invocations.add(new Tuple2<>(n, a));
        });
        iter.next();
        expect(iter, ")");
    }

    private void parseTopLevel(TokenIterator iter) {
        expect(iter, "(");
        String s = iter.next();

        switch (s) {
            case "assert_return" -> parseAssertReturn(iter);
            case "assert_trap" -> parseAssertTrap(iter);
            case "invoke" -> parseInvoke(iter, (n, a) -> invocations.add(new Tuple2<>(n, a)));
            default -> throwOnUnexpected(s, "assert_return", "assert_trap");
        }

    }

    private static InputStream openResourceStream(String className, String fileName) {
        final String prefix = className.replace(".", File.separator);
        final String name = Paths.get(prefix, fileName).toString();

        InputStream ret = WasmSpecSelectorFunctionProvider.class.getClassLoader().getResourceAsStream(name);
        if (ret == null) {
            throw new RuntimeException("Could not find resource " + name);
        }
        return ret;
    }

    public Iterable<BigInteger> startFunctionId() {
        return () -> invocations.stream()
                .map(t -> BigInteger.valueOf(exportedFunctions.get(t.v0)))
                .iterator();
//        return () -> IntStream.range(0, invocations.size()).mapToObj(BigInteger::valueOf).iterator();
    }

    private static Tuple2<BigInteger, Boolean> optToTuple(Optional<BigInteger> value) {
        return value.map(i -> new Tuple2<>(i, false)).orElse(new Tuple2<>(BigInteger.ZERO, true));
    }

    public Iterable<Tuple2<BigInteger, Boolean>> valueAndTopOfArgument(BigInteger invocation, BigInteger idx) {
        return List.of(optToTuple(invocations.get(invocation.intValueExact()).v1.get(idx.intValueExact())));
    }

    public Iterable<BigInteger> independentInvocationIds() {
        if(!executeIndependent) {
            return independentInvocations;
        }

        var ret = new HashSet<>(independentInvocations);
        dependentInvocations.forEach(ret::addAll);
        return ret;
    }

    public Iterable<Tuple2<BigInteger, BigInteger>> dependentInvocationIds() {
        if(executeIndependent) {
            return List.of();
        }

        List<Tuple2<BigInteger, BigInteger>> result = new ArrayList<>();

        for (var l : dependentInvocations) {
            Set<BigInteger> trapInvocations = assertTraps.stream().map(BigInteger::valueOf).collect(Collectors.toSet());
            SortedSet<BigInteger> returningInvocations = l.stream().filter(i -> !trapInvocations.contains(i)).collect(Collectors.toCollection(TreeSet::new));

            for (BigInteger thisInvocation : l.tailSet(l.first().add(BigInteger.ONE))) {
                BigInteger lastSuccessFulInvocation = returningInvocations.headSet(thisInvocation).last();
                result.add(new Tuple2<>(lastSuccessFulInvocation, thisInvocation));
            }
        }
        return result;
    }

    public Iterable<BigInteger> functionIdForInvocation(BigInteger currentInvocation) {
        return List.of(BigInteger.valueOf(exportedFunctions.get(invocations.get(currentInvocation.intValueExact()).v0)));
    }

    public Iterable<BigInteger> assertReturnInvocations() {
        return () -> assertReturns.stream().map(v -> BigInteger.valueOf(v.v0)).iterator();
    }

    public Iterable<BigInteger> assertTrapInvocations() {
        return () -> assertTraps.stream().map(BigInteger::valueOf).iterator();
    }

    public Iterable<Tuple2<BigInteger, Boolean>> valueAndTopOfReturn(BigInteger invocation, BigInteger idx) {
        return List.of(optToTuple(assertReturns.stream().filter(v -> v.v0 == invocation.intValueExact()).findFirst().get().v1.get(idx.intValueExact())));
    }

    public Iterable<Boolean> possiblyPreciseInvocation(BigInteger invocation) {
        if (invocations.get(invocation.intValueExact()).v1.stream().anyMatch(Optional::isEmpty)) {
            return List.of();
        }

        if (assertTraps.contains(invocation.intValueExact())) {
            return List.of(true);
        }

        Optional<Tuple2<Integer, List<Optional<BigInteger>>>> returnValuesIfAssertReturn = assertReturns.stream()
                .filter(v -> v.v0 == invocation.intValueExact()).findFirst();

        if (returnValuesIfAssertReturn.isEmpty()) {
            return List.of();
        }
        if (returnValuesIfAssertReturn.get().v1.stream().anyMatch(Optional::isEmpty)) {
            return List.of();
        }
        return List.of(true);
    }
}
