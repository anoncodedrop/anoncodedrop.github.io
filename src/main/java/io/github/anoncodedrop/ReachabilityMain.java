package io.github.anoncodedrop;

import com.google.gson.Gson;
import io.github.alisianoi.WParser;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import picocli.CommandLine;
import wien.secpriv.horst.cli.*;
import wien.secpriv.horst.data.Proposition;
import wien.secpriv.horst.data.Rule;
import wien.secpriv.horst.execution.*;
import wien.secpriv.horst.internals.SelectorFunctionHelper;
import wien.secpriv.horst.internals.error.handling.ExceptionThrowingErrorHandlerWithLocation;
import wien.secpriv.horst.tools.HorstFileParser;
import wien.secpriv.horst.tools.PredicateHelper;
import wien.secpriv.horst.translation.StandardZ3TranslationPipeline;
import wien.secpriv.horst.translation.TranslationPipeline;
import wien.secpriv.horst.translation.external.SmtLibTheory;
import wien.secpriv.horst.translation.visitors.TranslateToSmtLibVisitorState;
import wien.secpriv.horst.visitors.CachedReadableOperationsScope;
import wien.secpriv.horst.visitors.VisitorState;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@CommandLine.Command(name = "Main", mixinStandardHelpOptions = true)
public class ReachabilityMain implements Runnable {
    public static final SmtLibTheory externalTheory = new BitVectorTheory();

    @CommandLine.Mixin
    private VerbosityMixin verbosity;

    @CommandLine.Mixin
    private SmtOutDirMixin smtOutDir;

    @CommandLine.Mixin
    private JsonOutDirMixin jsonOutDir;

    @CommandLine.Option(names = {"--z3-query-timeout"}, description = "Timeout in milliseconds after which z3 executions are aborted (per query). A negative value disables the query timeout.", defaultValue = "-1")
    private long z3QueryTimeout;

    @CommandLine.Mixin
    private NoOutputQueryResultsMixin noOutputQueryResultsMixin;

    @CommandLine.Mixin
    private PruneAndInliningMixin pruneAndInliningMixin;

    @CommandLine.Mixin
    private SmtDialectMixin smtDialectMixin;

    @CommandLine.Option(names = {"--overapproximate-memory"}, description = "Timeout in milliseconds after which z3 executions are aborted (per query). A negative value disables the query timeout.", defaultValue = "false")
    private boolean overApproximateMemory;

    @CommandLine.Option(
            names = {"--spec-in-dir"},
            description = {"Directory files where spec files are stored."},
            required = true
    )
    private String specInDir;

    @CommandLine.Parameters
    private String[] args;


    public static void main(String[] args) {
        CommandLine.run(new ReachabilityMain(), args);
    }

    void parseSemantics(VisitorState state) {
        if (overApproximateMemory) {
            HorstFileParser.parseAllHorstFiles(state, WasmReachabilitySemanticsPredicateMemory.getSemanticsForFunctionTestsOverapproximateMemory());
        } else {
            HorstFileParser.parseAllHorstFiles(state, WasmReachabilitySemanticsPredicateMemory.getSemanticsForFunctionTests());
        }
    }

    public static Map<String, String> fromJson(InputStream inputStream) {
        try (var bufferedReader = new BufferedReader(new InputStreamReader(inputStream))) {
            Gson gson = new Gson();
            return gson.fromJson(bufferedReader, TestSpecification.class).queries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class TestSpecification {
        Map<String, String> queries;
    }

    @Override
    public void run() {
        configureLogger();

        for (String testId : args) {
            System.out.println("Running " + testId + "...");
            var path = Path.of(specInDir, testId + ".wasm");
            var jsonPath = Path.of(specInDir, testId + ".json");

            var module = new WParser().parse(path.toFile());
            var wasmSelectorFunctionProvider = new WasmSelectorFunctionProvider(module);

            try {
                List<ExecutionResult> executionResults = new ArrayList<>();
                List<ExecutionResultHandler> executionResultHandlers = new ArrayList<>(jsonOutDir.getExecutionResultHandler(path.getFileName().toString() + "_" + testId));
                if(!noOutputQueryResultsMixin.getExecutionResultHandler(testId).isEmpty()) {
                    executionResultHandlers.add(new ExecutionResultHandler.ConsoleOutputExecutionResultHandler(testId));
                }

                for (var test : fromJson(new FileInputStream(jsonPath.toFile())).entrySet()) {
                    TestCompTestsSelectorFunctionProvider testCompSelectorFunctionProvider;
                    List<String> additionalHorstFiles = new ArrayList<>();

                    switch (test.getKey()) {
                        case "call-unreachable":
                            testCompSelectorFunctionProvider = TestCompTestsSelectorFunctionProvider.forCallUnreachable(module, test.getValue());
                            additionalHorstFiles.add("resource:///horst/reachability-predicate-memory/testing/unreachable-call.horst");
                            break;
                        case "no-i32.add-overflow":
                            testCompSelectorFunctionProvider = TestCompTestsSelectorFunctionProvider.get(module, test.getValue());
                            additionalHorstFiles.add("resource:///horst/reachability-predicate-memory/testing/no-i32.add-overflow.horst");
                            break;
                        default:
                            if (test.getKey().startsWith("custom")) {
                                testCompSelectorFunctionProvider = TestCompTestsSelectorFunctionProvider.get(module, test.getValue());
                                additionalHorstFiles.add(Path.of(specInDir, test.getValue()).toString());
                            } else {
                                throw new IllegalArgumentException("No queries for " + test.getKey());
                            }
                    }

                    VisitorState state = new VisitorState();
                    state.errorHandler = new ExceptionThrowingErrorHandlerWithLocation(state.spans);
                    SelectorFunctionHelper selectorFunctionHelper = new SelectorFunctionHelper();
                    selectorFunctionHelper.registerProvider(wasmSelectorFunctionProvider);
                    selectorFunctionHelper.registerProvider(testCompSelectorFunctionProvider);

                    var embedderSpecPath = Path.of(specInDir, testId + ".embedder.json");
                    InputStream stream;
                    try {
                        stream = new FileInputStream(embedderSpecPath.toFile());
                    } catch (Exception ignored) {
                        stream = new ByteArrayInputStream("{}".getBytes());
                    }

                    var embedderSpecSelectionFunctionProvider = new EmbedderSpecSelectorFunctionProvider(module, stream);
                    selectorFunctionHelper.registerProvider(embedderSpecSelectionFunctionProvider);

                    state.setSelectorFunctionHelper(selectorFunctionHelper);

                    parseSemantics(state);
                    HorstFileParser.parseAllHorstFiles(state, additionalHorstFiles);

                    TranslationPipeline pipeline = StandardZ3TranslationPipeline.get(state, selectorFunctionHelper, externalTheory);
                    List<Rule> rules = pipeline.apply(new ArrayList<>(state.getRules().values()));
                    Set<Proposition.PredicateProposition> originalQueries = rules.stream().flatMap(r -> r.clauses.stream()).map(c -> c.conclusion).filter(PredicateHelper::isQueryOrTest).collect(Collectors.toSet());

                    List<QuerySpecificPreprocessingStrategy> querySpecificPreprocessingStrategies = initializeQueryPreprocessingStrategies(path);

                    QueryExecutor executor = new SmtGeneratingZ3QueryExecutor(params -> new TranslateToSmtLibVisitorState(params, externalTheory),
                            Map.of("fp.engine", "spacer"),
                            rules, ExecutionStrategy.Enum.all.getStrategy(),
                            querySpecificPreprocessingStrategies,
                            z3QueryTimeout,
                            smtDialectMixin.getDialect()
                    );
                    executionResults.addAll(originalQueries.stream().map(executor::executeQuery).collect(Collectors.toList()));
                }

                for (ExecutionResultHandler handler : executionResultHandlers) {
                    handler.handle(executionResults);
                }
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private List<QuerySpecificPreprocessingStrategy> initializeQueryPreprocessingStrategies(Path path) {
        List<QuerySpecificPreprocessingStrategy> querySpecificPreprocessingStrategies = new ArrayList<>(pruneAndInliningMixin.getQuerySpecificPreprocessingStrategy(externalTheory.getConstantFoldingTranslator(new CachedReadableOperationsScope(List.of()))));
        querySpecificPreprocessingStrategies.add(new FreeVarResolutionStrategy());
        String header = "";
        querySpecificPreprocessingStrategies.addAll(smtOutDir.getQuerySpecificPreprocessingStrategy(path.getFileName().toString(), header, externalTheory));

        if (noOutputQueryResultsMixin.getExecutionResultHandler(path.getFileName().toString()).isEmpty()) {
            querySpecificPreprocessingStrategies.add(new QuerySpecificPreprocessingStrategy() {
                @Override
                public Optional<List<Rule>> preprocessForQuery(List<Rule> list, Proposition.PredicateProposition predicateProposition) {
                    return Optional.empty();
                }
            });
        }

        return querySpecificPreprocessingStrategies;
    }

    private void configureLogger() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Level level = verbosity.getLoggerLevel();
        context.getConfiguration().getRootLogger().setLevel(level);
        Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);

        Appender consoleAppender = context.getConfiguration().getAppender("Console");
        context.getConfiguration().getRootLogger().addAppender(consoleAppender, level, null);
        BasicConfigurator.configure();
    }
}