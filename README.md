# Supplementary Material for Wappler

## Technical Report

A draft of the technical report can be found [here](files/wappler-tr.pdf).

The sources of the implementation can be found in the `src` directory of the accompanying repository.
## Experiments

All experiments are assumed to be executed in a cloned version of this repository, that is, after executing

```shell
git clone https://github.com/anoncodedrop/anoncodedrop.github.io.git wappler
cd wappler
```

### Needed Software

* `java` (at least 17),
* `wabt` (to compile wat/wast files to wasm) 
* `curl`, `tar`, `find`, `grep` (helpers for tests)
* `z3` (tests were run with version 4.12.1, older versions had worse performance)

### Wasm Specification

```shell
# download official test files from correct 
curl https://codeload.github.com/WebAssembly/spec/tar.gz/f2b62c3067ac7e9e367296378621ccbd4fee79c1 | tar -xz --strip=2 spec-f2b62c3067ac7e9e367296378621ccbd4fee79c1/test/core

# extract individual modules
mkdir out
./scripts/extract-modules.py core/*.wast

# move modules with unsupported features out of the way 
mkdir out/unsupported
cd out
mv -i imports-00{2,3}.* elem-00{2,3,4,5}.* exports-* linking-* start-* unsupported/

# compile wat files to wat
find -maxdepth 1 -iname '*.wat' -exec wat2wasm '{}' ';'

cd ..

# run tests in out/loop-000.spec on module out/loop-000.wasm with 10 seconds z3 timeout 
java -cp bin/wappler-0.0.1-SNAPSHOT.jar io.github.anoncodedrop.WasmSpecRunner --spec-in-dir=out --prune-strategy=aggressive --predicate-inlining-strategy=linear --z3-query-timeout 10000 loop-000

# run tests in out/align-000.spec on module out/align-000.wasm with 30 seconds z3 timeout, where the different test cases are assumed not to influence each other
java -cp bin/wappler-0.0.1-SNAPSHOT.jar io.github.anoncodedrop.WasmSpecRunner --spec-in-dir out --prune-strategy=aggressive --predicate-inlining-strategy=linear --z3-query-timeout 30000 --execute-independent align-000

# create output directory for smt files 
mkdir -p smt-out/wasm-spec/

# generate smt files without executing z3
java -cp bin/wappler-0.0.1-SNAPSHOT.jar io.github.anoncodedrop.WasmSpecRunner --spec-in-dir out --prune-strategy=aggressive --predicate-inlining-strategy=linear --no-output-query-results --smt-out-dir smt-out/wasm-spec loop-000
```

### Examples from Case Study

```shell
# run vulnerable versions of tests
java -cp bin/wappler-0.0.1-SNAPSHOT.jar io.github.anoncodedrop.ReachabilityMain --spec-in-dir files/reachability-spec --prune-strategy=aggressive --predicate-inlining-strategy=linear fig1 fig4

# run secured versions of 
java -cp bin/wappler-0.0.1-SNAPSHOT.jar io.github.anoncodedrop.ReachabilityMain --spec-in-dir files/reachability-spec --prune-strategy=aggressive --predicate-inlining-strategy=linear fig1-secure fig4-secure

# find problematic return values for fig 1
## create output directory
mkdir -p smt-out/reachability-spec
## create smt output (no inlining/pruning to preserve the structure of the program)
java -cp bin/wappler-0.0.1-SNAPSHOT.jar io.github.anoncodedrop.ReachabilityMain --spec-in-dir files/reachability-spec --no-output-query-results --smt-out-dir smt-out/reachability-spec fig1
## find out program counter of call_indirect (call_indirect happens at pc 2 of function 0, meaning that the return value will be on the top of the stack at pc 3)
grep -A19 callIndirectOverapproximated smt-out/reachability-spec/fig1.wasm.smt-testDontReturnNegative_0.smt
## look at concrete value for corresponding predicate in z3 model
z3 fp.print-answer=true fp.xform.inline_linear=false fp.xform.inline_eager=false smt-out/reachability-spec/fig1.wasm.smt-testDontReturnNegative_0.smt | grep -A1 MState_0_3
## the problematic return value is #x0000000080000000
```
