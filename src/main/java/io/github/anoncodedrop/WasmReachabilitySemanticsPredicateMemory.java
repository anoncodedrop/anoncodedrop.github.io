package io.github.anoncodedrop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WasmReachabilitySemanticsPredicateMemory {
    private WasmReachabilitySemanticsPredicateMemory() {
    }

    public static List<String> getSemantics() {
        return getSemantics(false);
    }

    public static List<String> getSemantics(boolean overapproximateMemory) {
        return List.of(
                "resource:///horst/selector-functions/selectors.horst",
                "resource:///horst/operations/math.horst",

                "resource:///horst/constants/opcodes.horst",
                "resource:///horst/constants/helpers.horst",

                // TODO: Allow integer semantics to be selected...
                "resource:///horst/types/bitvector/value.horst",
                "resource:///horst/operations/bitvector/value-domain.horst",

                "resource:///horst/reachability-predicate-memory/types/memory.horst",
                "resource:///horst/types/generic/option.horst",

                "resource:///horst/operations/generic/imprecision/freeval.horst",

                "resource:///horst/operations/value/value.horst",
                "resource:///horst/operations/value/option.horst",

                "resource:///horst/operations/bitvector/conversions.horst",
                "resource:///horst/operations/generic/conversions.horst",

                "resource:///horst/operations/bitvector/numerics.horst",
                "resource:///horst/operations/bitvector/abstract-comparisons.horst",
                "resource:///horst/operations/generic/numerics.horst",

                "resource:///horst/operations/bitvector/value-checks.horst",
                "resource:///horst/operations/value/value-checks.horst",

                "resource:///horst/operations/bitvector/memory.horst",
                "resource:///horst/reachability-predicate-memory/operations/memory-io-stub.horst",
                "resource:///horst/operations/generic/memory.horst",

                "resource:///horst/operations/value/tuple.horst",

                "resource:///horst/reachability-predicate-memory/operations/memory.horst",

                "resource:///horst/reachability-predicate-memory/predicates/states.horst",
                "resource:///horst/reachability-predicate-memory/instructions/numeric.horst",
                "resource:///horst/reachability-predicate-memory/instructions/parametric.horst",
                "resource:///horst/reachability-predicate-memory/instructions/variable.horst",
                overapproximateMemory ? "resource:///horst/reachability-predicate-memory/instructions/memory-overapproximated.horst" : "resource:///horst/reachability-predicate-memory/instructions/memory.horst",
                "resource:///horst/reachability-predicate-memory/instructions/control.horst"
        );
    }

    public static List<String> getSemanticsForFunctionTests() {
        List<String> files = new ArrayList<>(getSemantics());
        files.addAll(List.of(
                "resource:///horst/selector-functions/testing-call-unreachable.horst",
                "resource:///horst/reachability-predicate-memory/instructions/imported-functions.horst",
                "resource:///horst/reachability-predicate-memory/testing/init.horst",
                "resource:///horst/reachability-predicate-memory/testing/propagation.horst"
        ));
        // Optionals for testing
//        files.add("resource:///horst/reachability-predicate-memory/testing/result.horst");
//        files.add("resource:///horst/reachability-predicate-memory/testing/reachability.horst");
        return Collections.unmodifiableList(files);
    }

    public static List<String> getSemanticsForWasmSpecTests(boolean overapproximateMemory) {
        List<String> files = new ArrayList<>(getSemantics(overapproximateMemory));
        files.addAll(List.of(
                "resource:///horst/selector-functions/testing-wasm-spec.horst",
                "resource:///horst/reachability-predicate-memory/instructions/imported-functions.horst",
                "resource:///horst/reachability-predicate-memory/testing/init-wasm-spec.horst",
                "resource:///horst/reachability-predicate-memory/testing/propagation.horst"
        ));
        // Optionals for testing
        files.add("resource:///horst/reachability-predicate-memory/testing/wasm-spec.horst");
//        files.add("resource:///horst/reachability-predicate-memory/testing/result.horst");
//        files.add("resource:///horst/reachability-predicate-memory/testing/unreachable-call.horst");
//        files.add("resource:///horst/reachability-predicate-memory/testing/reachability.horst");
        return Collections.unmodifiableList(files);
    }

    public static Iterable<String> getSemanticsForFunctionTestsOverapproximateMemory() {
        List<String> files = new ArrayList<>(getSemantics(true));
        files.addAll(List.of(
                "resource:///horst/selector-functions/testing-call-unreachable.horst",
                "resource:///horst/reachability-predicate-memory/instructions/imported-functions.horst",
                "resource:///horst/reachability-predicate-memory/testing/init.horst",
                "resource:///horst/reachability-predicate-memory/testing/propagation.horst",
                "resource:///horst/reachability-predicate-memory/testing/unreachable-call.horst"
        ));
        return files;
    }
}
