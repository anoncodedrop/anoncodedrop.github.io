package io.github.anoncodedrop;

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
import wien.secpriv.horst.translation.PredicateInliningStrategy;
import wien.secpriv.horst.translation.PruneStrategy;
import wien.secpriv.horst.translation.StandardZ3TranslationPipeline;
import wien.secpriv.horst.translation.TranslationPipeline;
import wien.secpriv.horst.translation.external.SmtLibTheory;
import wien.secpriv.horst.translation.visitors.TranslateToSmtLibVisitorState;
import wien.secpriv.horst.visitors.CachedReadableOperationsScope;
import wien.secpriv.horst.visitors.VisitorState;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class WasmSpecRunner implements Runnable {
    public static final SmtLibTheory externalTheory = new BitVectorTheory();

    @CommandLine.Mixin
    private VerbosityMixin verbosity;

    @CommandLine.Mixin
    private SmtOutDirMixin smtOutDir;

    @CommandLine.Option(
            names = {"--spec-in-dir"},
            description = {"Directory files where spec files are stored."},
            required = true
    )
    private String specInDir;

    @CommandLine.Option(
            names = {"--execute-independent"},
            description = {"Execute all tests independent starting from the start configuration."},
            defaultValue = "false"
    )
    private boolean executeIndependent;

    @CommandLine.Option(names = {"--z3-query-timeout"}, description = "Timeout in milliseconds after which z3 executions are aborted (per query). A negative value disables the query timeout.", defaultValue = "-1")
    private long z3QueryTimeout;

    @CommandLine.Mixin
    private NoOutputQueryResultsMixin noOutputQueryResultsMixin;

    @CommandLine.Mixin
    private PruneAndInliningMixin pruneAndInliningMixin;

    @CommandLine.Mixin
    private SmtDialectMixin smtDialectMixin;

    @CommandLine.Parameters
    private String[] args;

    public static void main(String[] args) {
        CommandLine.run(new WasmSpecRunner(), args);
    }

    public void run() {
        configureLogger();

        for (String testId : args) {
            System.out.println("Running " + testId + "...");
            var path = Path.of(specInDir, testId + ".wasm");
            var specPath = Path.of(specInDir, testId + ".spec");

            var module = new WParser().parse(path.toFile());
            var wasmSelectorFunctionProvider = new WasmSelectorFunctionProvider(module);

            final WasmSpecSelectorFunctionProvider wasmSpecSelectorFunctionProvider;
            try {
                wasmSpecSelectorFunctionProvider = new WasmSpecSelectorFunctionProvider(new FileInputStream(specPath.toFile()), module, executeIndependent);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }

            VisitorState state = new VisitorState();
            state.errorHandler = new ExceptionThrowingErrorHandlerWithLocation(state.spans);
            SelectorFunctionHelper selectorFunctionHelper = new SelectorFunctionHelper();
            selectorFunctionHelper.registerProvider(wasmSpecSelectorFunctionProvider);
            selectorFunctionHelper.registerProvider(wasmSelectorFunctionProvider);

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
            HorstFileParser.parseAllHorstFiles(state, WasmReachabilitySemanticsPredicateMemory.getSemanticsForWasmSpecTests(false));

            TranslationPipeline pipeline = StandardZ3TranslationPipeline.get(state, selectorFunctionHelper, externalTheory);
            List<Rule> rules = pipeline.apply(new ArrayList<>(state.getRules().values()));
            Set<Proposition.PredicateProposition> originalQueries = rules.stream().flatMap(r -> r.clauses.stream()).map(c -> c.conclusion).filter(PredicateHelper::isQueryOrTest).collect(Collectors.toSet());

            final String header = "; testcase: " + testId + "\n";

            List<QuerySpecificPreprocessingStrategy> querySpecificPreprocessingStrategies = initializeQueryPreprocessingStrategies(path, header);

            QueryExecutor executor = new SmtGeneratingZ3QueryExecutor(params -> new TranslateToSmtLibVisitorState(header, params, externalTheory),
                    Map.of("fp.engine", "spacer"),
                    rules, ExecutionStrategy.Enum.all.getStrategy(),
                    querySpecificPreprocessingStrategies, z3QueryTimeout, smtDialectMixin.getDialect());


            var executionResults =  originalQueries.stream().map(executor::executeQuery).toList();

            List<ExecutionResultHandler> executionResultHandlers = new ArrayList<>();
            if(!noOutputQueryResultsMixin.getExecutionResultHandler(testId).isEmpty()) {
                executionResultHandlers.add(new ExecutionResultHandler.ConsoleOutputExecutionResultHandler(testId));
            }

            for (ExecutionResultHandler handler : executionResultHandlers) {
                handler.handle(executionResults);
            }
        }
    }

    private List<QuerySpecificPreprocessingStrategy> initializeQueryPreprocessingStrategies(Path path, String header) {
        PredicateInliningStrategy inlineTablePreprocessing = new PredicateInliningStrategy() {
            @Override
            public List<wien.secpriv.horst.data.Predicate> getInlineCandidates(List<Rule> list) {
                return list.stream().flatMap(r -> r.clauses.stream()).filter(c -> c.conclusion.predicate.name.startsWith("Table")).map(c -> c.conclusion.predicate).distinct().toList();
//                return List.of();
            }
        };

        List<QuerySpecificPreprocessingStrategy> querySpecificPreprocessingStrategies = new ArrayList<>();

        var constantFoldingTranslator = externalTheory.getConstantFoldingTranslator(new CachedReadableOperationsScope(List.of()));

        querySpecificPreprocessingStrategies.add(new FreeVarResolutionStrategy());
        querySpecificPreprocessingStrategies.add(new QuerySpecificPreprocessingStrategy.ApplyMediumStepTransformationPreprocessingStrategy(PruneStrategy.Enum.none.strategy, inlineTablePreprocessing, constantFoldingTranslator));
        querySpecificPreprocessingStrategies.addAll(pruneAndInliningMixin.getQuerySpecificPreprocessingStrategy(constantFoldingTranslator));
        querySpecificPreprocessingStrategies.addAll(smtOutDir.getQuerySpecificPreprocessingStrategy(path.getFileName().toString(), header, externalTheory));

        if (noOutputQueryResultsMixin.getExecutionResultHandler(path.toString()).isEmpty()) {
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
