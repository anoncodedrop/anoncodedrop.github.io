package io.github.anoncodedrop;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.alisianoi.expert.model.WViewFunction;
import io.github.alisianoi.expert.wexpert.WEFunction;
import io.github.alisianoi.expert.wexpert.WEImport;
import io.github.alisianoi.parser.model.WModule;
import io.github.alisianoi.parser.model.section.*;
import io.github.alisianoi.parser.model.type.WValueType;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

public class EmbedderSpecSelectorFunctionProvider {

    public static class FunctionSpec {
        private Boolean touchesMemory;
        private Boolean growsMemory;
        private Boolean touchesTable;
        private Boolean addsFunctions;

        private List<String> returnValues;
        private List<String> globals;

        private <E> E defaultOnNull(E v, E def) {
            Objects.requireNonNull(def);
            if (v == null) {
                return def;
            }
            return v;
        }

        private Optional<Boolean> touchesMemory() {
            return Optional.ofNullable(touchesMemory);
        }

        private Optional<Boolean> growsMemory() {
            return Optional.ofNullable(growsMemory);
        }

        private Optional<Boolean> addsFunctions() {
            return Optional.ofNullable(addsFunctions);
        }

        private Optional<Boolean> touchesTable() {
            return Optional.ofNullable(touchesTable);
        }

        private Optional<BigInteger> getReturnValueBound(int valueIndex, int boundIndex) {
            String[] bounds = returnValues.get(valueIndex).split(",");
            if (bounds[boundIndex].isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new BigInteger(bounds[boundIndex]));
        }

        private Optional<BigInteger> getGlobalBound(int valueIndex, int boundIndex) {
            String globalSpec = globals.get(valueIndex);
            if (globalSpec.equals("=")) {
                return Optional.empty();
            }

            String[] bounds = globalSpec.split(",");
            if (bounds.length == 0 || bounds[boundIndex].isEmpty()) { // for some reason, Java thinks returning an empty array on ",".split(",") is acceptable
                return Optional.empty();
            }
            return Optional.of(new BigInteger(bounds[boundIndex]));
        }
    }

    private final WViewFunction viewFunction;
    private final Map<String, Integer> functionNameToId = new HashMap<>();
    private final Map<BigInteger, String> idToFunctionName = new HashMap<>();
    private final Map<String, FunctionSpec> functionSpecs;
    private final List<WValueType> globalTypes;

    public EmbedderSpecSelectorFunctionProvider(WModule module, InputStream embedderSpec) {
        this.viewFunction = new WViewFunction();

        Optional<WSectionType> sectionType = module.findSectionType();
        Optional<WSectionImport> sectionImport = module.findSectionImport();
        Optional<WSectionGlobal> sectionGlobal = module.findSectionGlobal();
        Optional<WSectionCode> sectionCode = module.findSectionCode();
        Optional<WSectionFunction> sectionFunction = module.findSectionFunction();

        globalTypes = sectionGlobal.stream().flatMap(e -> e.getEntries().stream()).map(WSectionGlobal.Entry::getValueType).toList();

        if (sectionType.isPresent() && sectionImport.isPresent()) {
            WEImport.map(this.viewFunction, sectionType.get(), sectionImport.get());
        }

        if (sectionType.isPresent() && sectionCode.isPresent() && sectionFunction.isPresent()) {
            WEFunction.map(this.viewFunction, sectionType.get(), sectionCode.get(), sectionFunction.get());
        }

        if(sectionImport.isEmpty()) {
            functionSpecs = new HashMap<>();
            return;
        }

        int functionId = 0;
        var importedFunctions = sectionImport.get().makeFunctions();
        for (var function : viewFunction.getFunctions()) {
            // TODO: Clean this up -- needs support in parser
            try {
                function.getCounter();
            } catch (NullPointerException e) {
                String functionName = importedFunctions.get(functionId).getName();
                functionNameToId.put(functionName, functionId);
                idToFunctionName.put(BigInteger.valueOf(functionId), functionName);

                ++functionId;
            }
        }

        Gson gson = new Gson();

        Reader reader = new InputStreamReader(embedderSpec);

        var type = new TypeToken<Map<String, FunctionSpec>>() {
        };

        functionSpecs = gson.fromJson(reader, type.getType());
    }

    private List<Boolean> lookUpBoolean(BigInteger functionId, Function<FunctionSpec, Optional<Boolean>> extractor, boolean def) {
        var a = Optional.ofNullable(idToFunctionName.get(functionId));
        var b = a.flatMap(fn -> Optional.ofNullable(functionSpecs.get(fn)));
        var c = b.flatMap(extractor);
        var d = c.orElse(def);
        return List.of(d);
    }

    public Iterable<Boolean> touchesMemory(BigInteger functionId) {
        return lookUpBoolean(functionId, FunctionSpec::touchesMemory, true);
    }

    public Iterable<Boolean> growsMemory(BigInteger functionId) {
        return lookUpBoolean(functionId, FunctionSpec::growsMemory, true);
    }

    public Iterable<Boolean> touchesTable(BigInteger functionId) {
        return lookUpBoolean(functionId, FunctionSpec::touchesTable, false);
    }

    public Iterable<Boolean> addsFunctions(BigInteger functionId) {
        var v = lookUpBoolean(functionId, FunctionSpec::addsFunctions, false);
        return v;
    }

    private Iterable<BigInteger> getReturnValueBound(BigInteger functionId, BigInteger idx, int boundIndex, BigInteger boundOn32Bit) {
        var bound = Optional.ofNullable(functionSpecs.get(idToFunctionName.get(functionId))).flatMap(fs -> fs.getReturnValueBound(idx.intValueExact(), boundIndex));
        var type = viewFunction.getFunctions().get(functionId.intValueExact()).getResTypes().get(idx.intValueExact());
        return boundToIterable(boundOn32Bit, bound, type);
    }

    private List<BigInteger> boundToIterable(BigInteger boundOn32Bit, Optional<BigInteger> bound, WValueType type) {
        if (bound.isPresent()) {
            return List.of(bound.get());
        }

        if (type == WValueType.I32 || type == WValueType.F32) {
            return List.of(boundOn32Bit);
        }
        return List.of();
    }

    public Iterable<BigInteger> returnValueLowerBound(BigInteger functionId, BigInteger idx) {
        return getReturnValueBound(functionId, idx, 0, BigInteger.ZERO);
    }

    public Iterable<BigInteger> returnValueUpperBound(BigInteger functionId, BigInteger idx) {
        return getReturnValueBound(functionId, idx, 1, BigInteger.valueOf(2).pow(32));
    }

    public Iterable<BigInteger> getGlobalBound(BigInteger functionId, BigInteger idx, int boundIndex, BigInteger boundOn32Bit) {
        if (!touchesGlobal(functionId, idx.intValueExact())) {
            return List.of();
        }

        var bound = Optional.ofNullable(functionSpecs.get(idToFunctionName.get(functionId)))
                .flatMap(fs -> fs.getGlobalBound(idx.intValueExact(), boundIndex));

        var type = globalTypes.get(idx.intValueExact());

        return boundToIterable(boundOn32Bit, bound, type);
    }

    public Iterable<BigInteger> globalLowerBound(BigInteger functionId, BigInteger idx) {
        return getGlobalBound(functionId, idx, 0, BigInteger.ZERO);
    }

    public Iterable<BigInteger> globalUpperBound(BigInteger functionId, BigInteger idx) {
        return getGlobalBound(functionId, idx, 1, BigInteger.valueOf(2).pow(32));
    }

    private boolean touchesGlobal(BigInteger functionId, int idx) {
        return lookUpBoolean(functionId, f -> {
            var s = f.globals.get(idx);
            return Optional.of(!s.equals("="));
        }, true).get(0);
    }

    public Iterable<Boolean> touchesGlobal(BigInteger functionId, BigInteger idx) {
        return List.of(touchesGlobal(functionId, idx.intValueExact()));
    }
}
