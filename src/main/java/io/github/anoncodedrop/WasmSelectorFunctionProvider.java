package io.github.anoncodedrop;

import io.github.alisianoi.WParser;
import io.github.alisianoi.expert.model.WViewFunction;
import io.github.alisianoi.expert.model.api.WIBlock;
import io.github.alisianoi.expert.model.api.WICommand;
import io.github.alisianoi.expert.model.api.WIFunction;
import io.github.alisianoi.expert.model.block.WBlockIfElse;
import io.github.alisianoi.expert.model.block.WBlockLoop;
import io.github.alisianoi.expert.model.command.*;
import io.github.alisianoi.expert.wexpert.WEFunction;
import io.github.alisianoi.expert.wexpert.WEImport;
import io.github.alisianoi.parser.model.WModule;
import io.github.alisianoi.parser.model.instruction.WCode;
import io.github.alisianoi.parser.model.instruction.WInstruction;
import io.github.alisianoi.parser.model.instruction.WInstructionI32Const;
import io.github.alisianoi.parser.model.instruction.WInstructionI64Const;
import io.github.alisianoi.parser.model.section.*;
import io.github.alisianoi.parser.model.section.WSectionData.Entry;
import io.github.alisianoi.parser.model.type.WValueType;
import io.github.alisianoi.parser.stream.WStream;
import wien.secpriv.horst.data.tuples.Tuple2;
import wien.secpriv.horst.data.tuples.Tuple3;
import wien.secpriv.horst.internals.IntervalProvider;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class WasmSelectorFunctionProvider {
    private final WModule module;
    private final WViewFunction viewFunction;
    private final Map<BigInteger, List<WICommand>> flattenedFunctionCache = new HashMap<>();
    private final List<BigInteger> globalBitwidth = new ArrayList<>();
    private final Map<BigInteger, List<BigInteger>> argumentBitwidth = new HashMap<>();
    private final Map<BigInteger, List<BigInteger>> resultBitwidth = new HashMap<>();
    private final Map<BigInteger, List<BigInteger>> localBitwidth = new HashMap<>();
    private final Map<BigInteger, List<BigInteger>> stackSizes = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, BigInteger>> ifEnds = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, BigInteger>> endSteps = new HashMap<>();

    private Optional<Tuple3<Boolean, BigInteger, BigInteger>> memory = Optional.empty();
    private final Set<BigInteger> importedFunctions = new HashSet<>();
    private final Map<BigInteger, Map<BigInteger, List<BigInteger>>> breakDestinations = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, Boolean>> loopCall = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, Set<BigInteger>>> loopChangesLocal = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, Set<BigInteger>>> loopChangesGlobal = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, Boolean>> loopStoreMemory = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, Boolean>> loopGrowMemory = new HashMap<>();
    private final Map<BigInteger, Map<BigInteger, BigInteger>> amountOfReturnValues = new HashMap<>();
    private final Map<BigInteger, Set<BigInteger>> reachablePCsForFunctionId = new HashMap<>();

    private final IntervalProvider intervalProvider = new IntervalProvider();

    public WasmSelectorFunctionProvider(WStream wStream) {
        this(new WParser().parse(wStream));
    }

    public WasmSelectorFunctionProvider(String fullPath) {
        this(new WParser().parse(fullPath));
    }

    public WasmSelectorFunctionProvider(WModule module) {
        this.module = module;

        this.viewFunction = new WViewFunction();
        Optional<WSectionType> sectionType = module.findSectionType();
        Optional<WSectionImport> sectionImport = module.findSectionImport();
        Optional<WSectionMemory> sectionMemory = module.findSectionMemory();
        Optional<WSectionCode> sectionCode = module.findSectionCode();
        Optional<WSectionFunction> sectionFunction = module.findSectionFunction();

        if (sectionType.isPresent() && sectionImport.isPresent()) {
            WEImport.map(this.viewFunction, sectionType.get(), sectionImport.get());
        }

        if (sectionType.isPresent() && sectionCode.isPresent() && sectionFunction.isPresent()) {
            WEFunction.map(this.viewFunction, sectionType.get(), sectionCode.get(), sectionFunction.get());
        }

        if (module.findSectionGlobal().isPresent()) {
            globalBitwidth.addAll(module.findSectionGlobal().get().getEntries().stream().map(type -> (type.getValueType().equals(WValueType.I32) || type.getValueType().equals(WValueType.F32)) ? BigInteger.valueOf(32) : BigInteger.valueOf(64)).toList());
        }

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

        int functionId = 0;
        for (var function : viewFunction.getFunctions()) {
            // TODO: Clean this up -- needs support in parser
            try {
                function.getCounter();
            } catch (NullPointerException e) {
                importedFunctions.add(BigInteger.valueOf(functionId));
                argumentBitwidth.put(BigInteger.valueOf(functionId), function.getArgTypes().stream().map(type -> (type.equals(WValueType.I32) || type.equals(WValueType.F32)) ? BigInteger.valueOf(32) : BigInteger.valueOf(64)).toList());
                resultBitwidth.put(BigInteger.valueOf(functionId), function.getResTypes().stream().map(type -> (type.equals(WValueType.I32) || type.equals(WValueType.F32)) ? BigInteger.valueOf(32) : BigInteger.valueOf(64)).toList());
                ++functionId;
                continue;
            }

            StackLayoutHelper stackLayoutHelper = new StackLayoutHelper(function);
            stackLayoutHelper.reconstruct();

            stackSizes.put(BigInteger.valueOf(functionId), stackLayoutHelper.getStackSizesAsBigIntegers());
            flattenedFunctionCache.put(BigInteger.valueOf(functionId), stackLayoutHelper.flattenedBody);
            ifEnds.put(BigInteger.valueOf(functionId), stackLayoutHelper.getIfEndsBigIntegers());
            endSteps.put(BigInteger.valueOf(functionId), stackLayoutHelper.getEndStepsBigIntegers());
            breakDestinations.put(BigInteger.valueOf(functionId), stackLayoutHelper.getBreakDestinationsAsBigIntegers());
            amountOfReturnValues.put(BigInteger.valueOf(functionId), stackLayoutHelper.getAmountOfReturnValuesAsBigIntegers());
            reachablePCsForFunctionId.put(BigInteger.valueOf(functionId), stackLayoutHelper.getReachablePCsAsBigIntegers());
            loopCall.put(BigInteger.valueOf(functionId), stackLayoutHelper.getLoopCall());
            loopChangesLocal.put(BigInteger.valueOf(functionId), stackLayoutHelper.getLoopChangedLocals());
            loopChangesGlobal.put(BigInteger.valueOf(functionId), stackLayoutHelper.getLoopChangedGlobals());
            loopStoreMemory.put(BigInteger.valueOf(functionId), stackLayoutHelper.getLoopStoreMemory());
            loopGrowMemory.put(BigInteger.valueOf(functionId), stackLayoutHelper.getLoopGrowMemory());
            argumentBitwidth.put(BigInteger.valueOf(functionId), function.getArgTypes().stream().map(type -> (type.equals(WValueType.I32) || type.equals(WValueType.F32)) ? BigInteger.valueOf(32) : BigInteger.valueOf(64)).toList());
            resultBitwidth.put(BigInteger.valueOf(functionId), function.getResTypes().stream().map(type -> (type.equals(WValueType.I32) || type.equals(WValueType.F32)) ? BigInteger.valueOf(32) : BigInteger.valueOf(64)).toList());
            localBitwidth.put(BigInteger.valueOf(functionId), function.getLocalTypes().stream().map(type -> (type.equals(WValueType.I32) || type.equals(WValueType.F32)) ? BigInteger.valueOf(32) : BigInteger.valueOf(64)).toList());

//            for (Integer pc : IntStream.range(0,stackLayoutHelper.flattenedBody.size()).boxed().toList()) {
//                System.out.println("fid = " + functionId + ", pc = " + pc + ", cmd = " + stackLayoutHelper.flattenedBody.get(pc));
//            }

            ++functionId;
        }
    }

    public Iterable<BigInteger> functionIds() {
        if (true) {
//            uncomment this if you only want to generate to code of a particular function
//            return List.of(BigInteger.valueOf(0));
        }

        return IntStream.range(0, viewFunction.getFunctions().size())
                .mapToObj(BigInteger::valueOf)
                .filter(fid -> !importedFunctions.contains(fid))
                .collect(Collectors.toList());
    }

    public Iterable<BigInteger> importedFunctionIds() {
        return importedFunctions;
    }

    public Iterable<Boolean> isImportedFunction(BigInteger fid) {
        return List.of(importedFunctions.contains(fid));
    }

    public Iterable<Boolean> isImportedMemory(BigInteger fid) {
        return List.of(false);
    }

    public Iterable<BigInteger> maxPcForFunctionId(BigInteger functionId) {
        if (importedFunctions.contains(functionId)) {
            return List.of(BigInteger.ZERO);
        }
        return List.of(BigInteger.valueOf(flattenedFunctionCache.get(functionId).size()));
    }

    public Iterable<BigInteger> globalCount() {
        int importedGlobalCount = module.findSectionImport().map(imports -> imports.getStats().getIdxOfGlobals().size()).orElse(0);
        return List.of(BigInteger.valueOf(
                importedGlobalCount +
                        module.findSectionGlobal()
                                .map(globals -> globals.getEntries().size())
                                .orElse(0)));
    }

    public Iterable<BigInteger> localCountForFunctionId(BigInteger functionId) {
        WIFunction function = viewFunction.getFunctions().get(functionId.intValueExact());
        int argumentCount = Optional.ofNullable(function.getArgTypes()).orElseGet(List::of).size();
        int localCount = Optional.ofNullable(function.getLocalTypes()).orElseGet(List::of).size();
        return List.of(BigInteger.valueOf(argumentCount + localCount));
    }

    public Iterable<BigInteger> argumentCountForFunctionId(BigInteger functionId) {
        if (functionId.intValueExact() >= viewFunction.size()) {
            //TODO investigate why this is necessary
            return List.of();
        }

        WIFunction function = viewFunction.getFunctions().get(functionId.intValueExact());
        int argumentCount = Optional.ofNullable(function.getArgTypes()).orElseGet(List::of).size();
        return List.of(BigInteger.valueOf(argumentCount));
    }

    private static <E> Optional<E> get(int i, List<E> l) {
        if(l.size() < i) {
            return Optional.empty();
        }
        return Optional.of(l.get(i));
    }

    public Iterable<BigInteger> argumentCountForTypeId(BigInteger typeId) {
        return module.findSectionType()
                .flatMap(st -> get(typeId.intValueExact(), st.getFunctionTypes()))
                .map(te -> te.getArgTypes().size())
                .map(BigInteger::valueOf)
                .stream() .collect(Collectors.toList());
    }

    public Iterable<BigInteger> returnCountForTypeId(BigInteger typeId) {
        return module.findSectionType()
                .flatMap(st -> get(typeId.intValueExact(), st.getFunctionTypes()))
                .map(te -> te.getResTypes().size())
                .map(BigInteger::valueOf)
                .stream() .collect(Collectors.toList());
    }

    public Iterable<BigInteger> bitwidthForGlobal(BigInteger idx) {
        if (idx.intValueExact() >= globalBitwidth.size()) {
            return List.of();
        }
        return List.of(globalBitwidth.get(idx.intValueExact()));
    }

    public Iterable<BigInteger> bitwidthForArgument(BigInteger functionId, BigInteger idx) {
        List<BigInteger> arguments = argumentBitwidth.getOrDefault(functionId, List.of());
        if (idx.intValueExact() >= arguments.size()) {
            return List.of();
        }
        return List.of(arguments.get(idx.intValueExact()));
    }

    public Iterable<BigInteger> bitwidthForResult(BigInteger functionId, BigInteger idx) {
        List<BigInteger> results = resultBitwidth.getOrDefault(functionId, List.of());
        if (idx.intValueExact() >= results.size()) {
            return List.of();
        }
        return List.of(results.get(idx.intValueExact()));
    }

    public Iterable<BigInteger> bitwidthForLocal(BigInteger functionId, BigInteger idx) {
        int _I = idx.intValueExact();
        List<BigInteger> arguments = argumentBitwidth.getOrDefault(functionId, List.of());
        if (_I < arguments.size()) {
            return List.of(arguments.get(_I));
        }
        _I -= arguments.size();

        List<BigInteger> locals = localBitwidth.getOrDefault(functionId, List.of());
        if (_I >= locals.size()) {
            return List.of();
        }
        return List.of(locals.get(_I));
    }

    public Iterable<BigInteger> returnCountForFunctionId(BigInteger functionId) {
        WIFunction function = viewFunction.getFunctions().get(functionId.intValueExact());
        return List.of(BigInteger.valueOf(function.getResTypes().size()));
    }

    public Iterable<BigInteger> exitPointsForFunctionId(BigInteger functionId) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        final var reachablePcs = reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet());

        return Stream.concat(
                IntStream.range(0, flattenedBody.size()).filter(i -> flattenedBody.get(i) != null && flattenedBody.get(i).getCode() == WCode.ret).mapToObj(BigInteger::valueOf),
                Stream.of(BigInteger.valueOf(flattenedBody.size() - 1)))
                .filter(reachablePcs::contains)
                .collect(Collectors.toList());
    }

    public Iterable<BigInteger> blocksForFunctionId(BigInteger functionId) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        return IntStream.range(0, flattenedBody.size())
                .filter(i -> {
                    WICommand cmd = flattenedBody.get(i);
                    return cmd instanceof WIBlock && !(cmd instanceof WBlockIfElse) && (!(cmd instanceof WBlockLoop) || breakDestinations.get(functionId).values().stream().noneMatch(br -> br.contains(BigInteger.valueOf(i))));
                }).mapToObj(BigInteger::valueOf)
                .collect(Collectors.toList());
    }

    public Iterable<BigInteger> loopsForFunctionId(BigInteger functionId) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        return IntStream.range(0, flattenedBody.size())
                .filter(i -> {
                    WICommand cmd = flattenedBody.get(i);
                    return cmd instanceof WBlockLoop && breakDestinations.get(functionId).values().stream().anyMatch(br -> br.contains(BigInteger.valueOf(i)));
                }).mapToObj(BigInteger::valueOf)
                .collect(Collectors.toList());
    }

    public Iterable<Tuple2<BigInteger, BigInteger>> endsForFunctionId(BigInteger functionId) {
        final var reachablePcs = reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet());
        return this.endSteps.getOrDefault(functionId, Map.of())
                .entrySet()
                .stream()
                .filter(e -> e.getKey().intValueExact() < flattenedFunctionCache.get(functionId).size() - 1)
                .filter(e -> reachablePcs.contains(e.getKey()))
                .map(e -> new Tuple2<>(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    public Iterable<BigInteger> joinsForFunctionId(BigInteger functionId) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);

        Stream<BigInteger> endJoins =
                this.endSteps.getOrDefault(functionId, Map.of())
                        .values()
                        .stream();

        Stream<BigInteger> x = IntStream.range(0, flattenedBody.size())
                .filter(i -> flattenedBody.get(i) instanceof WBreakIf || flattenedBody.get(i) instanceof WCallIndirect)
                .map(i -> i + 1)
                .mapToObj(BigInteger::valueOf);

        return Stream.concat(endJoins, x).distinct().toList();
    }

    public Iterable<BigInteger> stackSizeForFunctionIdAndPc(BigInteger functionId, BigInteger pc) {
        if (importedFunctions.contains(functionId)) {
            return List.of(BigInteger.ZERO);
        }
        if (!reachablePCsForFunctionId.get(functionId).contains(pc)) {
            return List.of();
        }
        if (stackSizes.get(functionId).size() <= pc.intValueExact()) {
            return List.of();
        }
        return List.of(stackSizes.get(functionId).get(pc.intValueExact()));
    }

    public Iterable<BigInteger> immediateForFunctionIdAndPc(BigInteger functionId, BigInteger pc) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);

        if (pc.intValueExact() >= flattenedBody.size() || !reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet()).contains(pc)) {
            return List.of();
        }

        WICommand cmd = flattenedBody.get(pc.intValueExact());

        return switch (cmd.getCode()) {
            case WCode.globalGet, WCode.globalSet, WCode.localGet, WCode.localSet, WCode.localTee -> List.of(BigInteger.valueOf(((WVariableIdx) cmd).getIndex()));
            case WCode.call -> List.of(BigInteger.valueOf(((WCall) cmd).getFuncIdx()));
            case WCode.call_indirect -> List.of(BigInteger.valueOf(((WCallIndirect) cmd).getTypeIdx()));
            default -> List.of();
        };
    }

    public Iterable<BigInteger> initialTableSize() {
        return module.findSectionTable().flatMap(
                t -> {
                    if (t.getTables().size() == 0) {
                        return Optional.empty();
                    }
                    return Optional.of(t.getTables().get(0).getMinLimit());
                }
        ).map(BigInteger::valueOf).stream().toList();
    }

    public Iterable<Tuple2<Boolean, BigInteger>> initialTableData(BigInteger idx) {
        var bla = module.findSectionTable().map(
                ts ->
                        Optional.ofNullable(ts.getTables().get(0).getFuncIdx().get(idx.intValueExact()))
                                .map(e -> new Tuple2<>(true, BigInteger.valueOf(e)))
                                .orElseGet(() -> new Tuple2<>(false, BigInteger.ZERO))
        ).stream().toList();
        return bla;
    }

    public Iterable<Boolean> tableImported() {
        var optSectionImport = module.findSectionImport();

        if (optSectionImport.isEmpty()) {
            return List.of(false);
        }

        return List.of(optSectionImport.get().getEntries().stream().anyMatch(e -> e instanceof WSectionImport.TableEntry));
    }


    public Iterable<BigInteger> ifs(BigInteger functionId) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        var reachablePcs = reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet());
        return IntStream.range(0, flattenedBody.size())
                .filter(i -> flattenedBody.get(i) instanceof WBlockIfElse)
                .mapToObj(BigInteger::valueOf)
                .filter(i -> reachablePcs.contains(i))
                .collect(Collectors.toList());
    }

    public Iterable<BigInteger> elseForIf(BigInteger functionId, BigInteger pc) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        if (flattenedBody.get(pc.intValueExact()) instanceof WBlockIfElse ifElse) {
            return List.of(BigInteger.valueOf(flattenedBody.indexOf(
                    ifElse.getBlockElse()
            )));
        }
        throw new IllegalArgumentException();
    }

    public Iterable<BigInteger> endForIf(BigInteger functionId, BigInteger pc) {
        return List.of(this.ifEnds.getOrDefault(functionId, Map.of()).get(pc));
    }

    public Iterable<BigInteger> binOps() {
        return Stream.of(
                WCode.i32eq,
                WCode.i32ne,
                WCode.i32lt_s,
                WCode.i32lt_u,
                WCode.i32gt_s,
                WCode.i32gt_u,
                WCode.i32le_s,
                WCode.i32le_u,
                WCode.i32ge_s,
                WCode.i32ge_u,

                WCode.i64eq,
                WCode.i64ne,
                WCode.i64lt_s,
                WCode.i64lt_u,
                WCode.i64gt_s,
                WCode.i64gt_u,
                WCode.i64le_s,
                WCode.i64le_u,
                WCode.i64ge_s,
                WCode.i64ge_u,

                WCode.f32eq,
                WCode.f32ne,
                WCode.f32lt,
                WCode.f32gt,
                WCode.f32le,
                WCode.f32ge,

                WCode.f64eq,
                WCode.f64ne,
                WCode.f64lt,
                WCode.f64gt,
                WCode.f64le,
                WCode.f64ge,

                WCode.i32add,
                WCode.i32sub,
                WCode.i32mul,

                WCode.i32and,
                WCode.i32or,
                WCode.i32xor,
                WCode.i32shl,
                WCode.i32shr_s,
                WCode.i32shr_u,
                WCode.i32rotl,
                WCode.i32rotr,

                WCode.i64add,
                WCode.i64sub,
                WCode.i64mul,

                WCode.i64and,
                WCode.i64or,
                WCode.i64xor,
                WCode.i64shl,
                WCode.i64shr_s,
                WCode.i64shr_u,
                WCode.i64rotl,
                WCode.i64rotr,

                WCode.f32add,
                WCode.f32sub,
                WCode.f32mul,
                WCode.f32div,
                WCode.f32min,
                WCode.f32max,
                WCode.f32copysign,

                WCode.f64add,
                WCode.f64sub,
                WCode.f64mul,
                WCode.f64div,
                WCode.f64min,
                WCode.f64max,
                WCode.f64copysign
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> trappingBinOps() {
        return Stream.of(
                WCode.i32div_s,
                WCode.i32div_u,
                WCode.i32rem_s,
                WCode.i32rem_u,

                WCode.i64div_s,
                WCode.i64div_u,
                WCode.i64rem_s,
                WCode.i64rem_u
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> unOps() {
        return Stream.of(
                WCode.i32eqz,
                WCode.i32clz,
                WCode.i32ctz,
                WCode.i32popcnt,

                WCode.i64eqz,
                WCode.i64clz,
                WCode.i64ctz,
                WCode.i64popcnt,

                WCode.f32abs,
                WCode.f32neg,
                WCode.f32ceil,
                WCode.f32floor,
                WCode.f32trunc,
                WCode.f32nearest,
                WCode.f32sqrt,

                WCode.f64abs,
                WCode.f64neg,
                WCode.f64ceil,
                WCode.f64floor,
                WCode.f64trunc,
                WCode.f64nearest,
                WCode.f64sqrt
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> loadOps() {
        return Stream.of(
                WCode.i32load,
                WCode.i64load,
                WCode.f32load,
                WCode.f64load,
                WCode.i32load8_s,
                WCode.i32load8_u,
                WCode.i32load16_s,
                WCode.i32load16_u,
                WCode.i64load8_s,
                WCode.i64load8_u,
                WCode.i64load16_s,
                WCode.i64load16_u,
                WCode.i64load32_s,
                WCode.i64load32_u
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> storeOps() {
        return Stream.of(
                WCode.i32store,
                WCode.i64store,
                WCode.f32store,
                WCode.f64store,

                WCode.i32store8,
                WCode.i32store16,
                WCode.i64store8,
                WCode.i64store16,
                WCode.i64store32
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> cvtOps() {
        return Stream.of(
                WCode.i32wrap_i64,
                WCode.i64extend_i32_s,
                WCode.i64extend_i32_u,
                WCode.f32convert_i32_s,
                WCode.f32convert_i32_u,
                WCode.f32convert_i64_s,
                WCode.f32convert_i64_u,
                WCode.f32demote_f64,
                WCode.f64convert_i32_s,
                WCode.f64convert_i32_u,
                WCode.f64convert_i64_s,
                WCode.f64convert_i64_u,
                WCode.f64promote_f32,
                WCode.i32reinterpret_f32,
                WCode.i64reinterpret_f64,
                WCode.f32reinterpret_i32,
                WCode.f64reinterpret_i64
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> trappingCvtOps() {
        return Stream.of(
                WCode.i32trunc_f32_s,
                WCode.i32trunc_f32_u,
                WCode.i32trunc_f64_s,
                WCode.i32trunc_f64_u,

                WCode.i64trunc_f32_s,
                WCode.i64trunc_f32_u,
                WCode.i64trunc_f64_s,
                WCode.i64trunc_f64_u
        ).map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Iterable<BigInteger> memoryOffsetForFunctionIdAndPc(BigInteger functionId, BigInteger pc) {
        WICommand cmd = getFlattenedFunctionBody(functionId).get(pc.intValueExact());

        if (cmd instanceof WMemoryLoadOrStore memCmd) {
            return List.of(BigInteger.valueOf(memCmd.getOffset()));
        }

        return List.of();
    }

    public Iterable<Tuple2<BigInteger, Boolean>> valueAndTopOfGlobal(BigInteger index) {
        List<Tuple2<BigInteger, Boolean>> valueAndTop = new ArrayList<>();

        module.findSectionImport().ifPresent(sectionImport ->
                valueAndTop.addAll(
                        Collections.nCopies(sectionImport.getStats().getIdxOfGlobals().size(), new Tuple2<>(BigInteger.ZERO, true))
                ));

        module.findSectionGlobal().ifPresent(sectionGlobal -> {
            for (var entry : sectionGlobal.getEntries()) {
                List<WInstruction> initialization = entry.getExpression();
                if (initialization.size() == 1) {
                    WInstruction cmd = initialization.get(0);
                    valueAndTop.add(switch (cmd.getCode()) {
                        case WCode.i32const -> new Tuple2<>(signedToUnsigned(((WInstructionI32Const) cmd).getValue()), false);
                        case WCode.i64const -> new Tuple2<>(signedToUnsigned(((WInstructionI64Const) cmd).getValue()), false);
                        default -> new Tuple2<>(BigInteger.ZERO, true);
                    });
                } else {
                    valueAndTop.add(new Tuple2<>(BigInteger.ZERO, true));
                }
            }
        });

        return Collections.singletonList(valueAndTop.get(index.intValueExact()));
    }

    private List<WICommand> getFlattenedFunctionBody(BigInteger functionId) {
        return flattenedFunctionCache.get(functionId);
    }

    public Iterable<BigInteger> pcsForFunctionIdAndOpcode(BigInteger functionId, BigInteger opcode) {
        final List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        final Set<BigInteger> reachablePcs = reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet());

        return IntStream.range(0, flattenedBody.size())
                .filter(i -> flattenedBody.get(i) != null && flattenedBody.get(i).getCode() == opcode.intValueExact())
                .mapToObj(BigInteger::valueOf)
                .filter(reachablePcs::contains)
                .collect(Collectors.toList());
    }

    private static BigInteger signedToUnsigned(int i) {
        return BigInteger.valueOf(0x00000000FFFFFFFFL & i);
    }

    private static BigInteger signedToUnsigned(long i) {
        return BigInteger.valueOf(0x00000000FFFFFFFFL & (i >> 32)).shiftLeft(32)
                .or(BigInteger.valueOf(0x00000000FFFFFFFFL & i));
    }

    public Iterable<Tuple3<BigInteger, BigInteger, Boolean>> pcsAndValueAndTopOfConstsForFunctionId(BigInteger functionId) {
        List<Tuple3<BigInteger, BigInteger, Boolean>> pcsAndValuesAndTop = new ArrayList<>();

        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        var reachablePcs = reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet());

        for (int pc = 0; pc < flattenedBody.size(); ++pc) {
            if (!reachablePcs.contains(BigInteger.valueOf(pc))) {
                continue;
            }

            WICommand cmd = flattenedBody.get(pc);
            if (cmd == null) {
                continue;
            }
            switch (cmd.getCode()) {
                case WCode.f32const, WCode.f64const -> pcsAndValuesAndTop.add(new Tuple3<>(BigInteger.valueOf(pc), BigInteger.ZERO, true));
                case WCode.i32const -> pcsAndValuesAndTop.add(new Tuple3<>(BigInteger.valueOf(pc), signedToUnsigned(((WI32Const) cmd).getValue()), false));
                case WCode.i64const -> pcsAndValuesAndTop.add(new Tuple3<>(BigInteger.valueOf(pc), signedToUnsigned(((WI64Const) cmd).getValue()), false));
            }
        }

        return pcsAndValuesAndTop;
    }

    public Iterable<Tuple2<BigInteger, BigInteger>> pcsAndIndexOfGlobalGetsFunctionId(BigInteger functionId) {
        List<Tuple2<BigInteger, BigInteger>> pcsAndIndex = new ArrayList<>();

        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);

        for (int pc = 0; pc < flattenedBody.size(); ++pc) {
            WICommand cmd = flattenedBody.get(pc);
            if (cmd.getCode() == WCode.globalGet) {
                pcsAndIndex.add(new Tuple2<>(BigInteger.valueOf(pc), BigInteger.valueOf(((WVariableIdx) cmd).getIndex())));
            }
        }

        return pcsAndIndex;
    }

    public Iterable<Boolean> localModifiedInLoopBlock(BigInteger functionId, BigInteger pc, BigInteger idx) {
        return List.of(loopChangesLocal.get(functionId).getOrDefault(pc, Set.of()).contains(idx));
    }

    public Iterable<Boolean> globalModifiedInLoopBlock(BigInteger functionId, BigInteger pc, BigInteger idx) {
        if (loopCall.get(functionId).getOrDefault(pc, false)) {
            return List.of(true);
        }

        return List.of(loopChangesGlobal.get(functionId).getOrDefault(pc, Set.of()).contains(idx));
    }

    public Iterable<Boolean> memoryStoreInLoopBlock(BigInteger functionId, BigInteger pc) {
        if (loopCall.get(functionId).getOrDefault(pc, false)) {
            return List.of(true);
        }

        return List.of(loopStoreMemory.get(functionId).getOrDefault(pc, false));
    }

    public Iterable<Boolean> memoryGrowInLoopBlock(BigInteger functionId, BigInteger pc) {
        if (loopCall.get(functionId).containsKey(pc) && loopCall.get(functionId).get(pc)) {
            return List.of(true);
        }

        if (!loopGrowMemory.get(functionId).containsKey(pc)) {
            return List.of(false);
        }
        return List.of(loopGrowMemory.get(functionId).get(pc));
    }

    public Iterable<BigInteger> breakDestinations(BigInteger functionId, BigInteger pc) {
        return breakDestinations.get(functionId).get(pc);
    }

    public Iterable<BigInteger> getAmountOfReturnValuesInBlock(BigInteger functionId, BigInteger pc) {
        return List.of(amountOfReturnValues.get(functionId).get(pc));
    }

    public Iterable<BigInteger> sizeOfBreakTable(BigInteger functionId, BigInteger pc) {
        return List.of(BigInteger.valueOf(breakDestinations.get(functionId).get(pc).size()));
    }

    public Iterable<BigInteger> breakTableDestinations(BigInteger functionId, BigInteger pc, BigInteger index) {
        return List.of(breakDestinations.get(functionId).get(pc).get(index.intValueExact()));
    }

    public Iterable<BigInteger> pcsForFunctionId(BigInteger functionId) {
        List<WICommand> body = getFlattenedFunctionBody(functionId);
        final var reachablePcs = reachablePCsForFunctionId.getOrDefault(functionId, Collections.emptySet());
        return IntStream.range(0, body.size()).mapToObj(BigInteger::valueOf).filter(reachablePcs::contains).collect(Collectors.toList());
    }

    public Iterable<BigInteger> possibleCallTargetsForType(BigInteger typeIdx) {
        int tableIdx = 0;
        Optional<WSectionTable> optFuncRefTable = module.findSectionTable();

        if (optFuncRefTable.isEmpty()) {
            return List.of();
        }

        var funcRefTable = optFuncRefTable.get().getTables().get(tableIdx);

        Map<Integer, Integer> functionTypes = module.findSectionFunction().get().getFuncIdxToTypeIdx();

        var functionTypeEntries = module.findSectionType().get().getFunctionTypes();
        var functionTypeEntry = functionTypeEntries.get(typeIdx.intValueExact());

        var duplicates = IntStream.range(0, functionTypeEntries.size())
                .filter(i -> functionTypeEntry.equals(functionTypeEntries.get(i)))
                .boxed()
                .collect(Collectors.toSet());

        return funcRefTable.getFuncIdx().values().stream()
                .filter(e -> duplicates.contains(functionTypes.get(e)))
                .map(BigInteger::valueOf)
                .collect(Collectors.toList());
    }

    public Iterable<Tuple2<BigInteger, BigInteger>> possibleCallTargets(BigInteger functionId, BigInteger pc) {
        List<WICommand> flattenedBody = getFlattenedFunctionBody(functionId);
        if (flattenedBody.get(pc.intValueExact()) instanceof WCallIndirect call) {
            int tableIdx = call.getTableIdx();
            Optional<WSectionTable> optFuncRefTable = module.findSectionTable();

            if (optFuncRefTable.isEmpty()) {
                return List.of();
            }

            var funcRefTable = optFuncRefTable.get().getTables().get(tableIdx);


            Map<Integer, Integer> functionTypes = module.findSectionFunction().get().getFuncIdxToTypeIdx();

            var functionTypeEntries = module.findSectionType().get().getFunctionTypes();
            var functionTypeEntry = functionTypeEntries.get(call.getTypeIdx());

            var duplicates = IntStream.range(0, functionTypeEntries.size())
                    .filter(i -> functionTypeEntry.equals(functionTypeEntries.get(i)))
                    .boxed()
                    .collect(Collectors.toSet());

            return funcRefTable.getFuncIdx().entrySet().stream()
                    //.filter(e -> functionTypes.get(e.getValue()) == call.getTypeIdx())
                    .filter(e -> duplicates.contains(functionTypes.get(e.getValue())))
                    .map(e -> new Tuple2<>(BigInteger.valueOf(e.getKey()), BigInteger.valueOf(e.getValue())))
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    public Iterable<BigInteger> interval(BigInteger start, BigInteger end) {
        return intervalProvider.interval(start, end);
    }

    public Iterable<Boolean> onlyIf(Boolean b) {
        return b ? List.of(true) : List.of();
    }

    public Iterable<Tuple2<BigInteger, BigInteger>> datasegmentsWithPositions() {
        List<Tuple2<BigInteger, BigInteger>> res = new ArrayList<>();
        Optional<WSectionData> data = module.findSectionData();
        if (data.isPresent()) {
            List<Entry> entries = data.get().getEntries();
            entries.forEach(e -> {
                //TODO rem: this was not what memory index is for
                long start = e.getOffset().orElse(0); // TODO do something smarter than that
                byte[] bytes = e.getPayload();
                for (int i = 0; i < bytes.length; i++) {
                    // A byte in Java is signed, so logical and with 0xFF to force it to a correct value
                    res.add(new Tuple2<>(BigInteger.valueOf(start + i), BigInteger.valueOf(bytes[i] & 0xFF)));
                }
            });
        }

        return res;
    }

    public Iterable<Boolean> isMemoryPresent() {
        return List.of(memory.isPresent());
    }

    public Iterable<Boolean> isMemoryImported() {
        return List.of(memory.map(m -> m.v0).orElse(false));
    }

    public Iterable<BigInteger> getMemoryMin() {
        return List.of(memory.map(m -> m.v1).orElse(BigInteger.ZERO));
    }

    public Iterable<BigInteger> getMemoryMax() {
        return List.of(memory.map(m -> m.v2).orElse(BigInteger.valueOf(65535)));
    }

    public Iterable<BigInteger> getHighestDataSegmentIndex() {
        Optional<WSectionData> data = module.findSectionData();
        if (data.isPresent()) {
            List<Entry> entries = data.get().getEntries();
            return List.of(
                    entries.stream()
                            .map(e -> BigInteger.valueOf(e.getMemoryIndex().get() + e.getOffset().get() + e.getPayload().length - 1))
                            .max(Comparator.naturalOrder())
                            .get()
            );
        }

        return List.of();
    }
}
