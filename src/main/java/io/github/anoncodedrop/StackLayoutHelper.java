package io.github.anoncodedrop;

import io.github.alisianoi.expert.model.api.WIBlock;
import io.github.alisianoi.expert.model.api.WICommand;
import io.github.alisianoi.expert.model.api.WIFunction;
import io.github.alisianoi.expert.model.block.WBlock;
import io.github.alisianoi.expert.model.block.WBlockIfElse;
import io.github.alisianoi.expert.model.block.WBlockLoop;
import io.github.alisianoi.expert.model.command.*;
import io.github.alisianoi.parser.model.instruction.WCode;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StackLayoutHelper {
    public final List<Integer> stackSizes = new ArrayList<>();
    public final List<WICommand> flattenedBody = new ArrayList<>();
    public final Map<Integer, Integer> ifEnds = new HashMap<>();
    public final Map<Integer, Integer> endSteps = new HashMap<>();
    private final Stack<Boolean> operandStack = new Stack<>();
    private final Stack<ControlFrame> controlStack = new Stack<>();
    private final LinkedList<Boolean> loopCallStack = new LinkedList<>();
    private final Map<BigInteger, Boolean> loopCall = new HashMap<>();
    private final Stack<Set<BigInteger>> loopChangedGlobalsStack = new Stack<>();
    private final Map<BigInteger, Set<BigInteger>> loopChangedGlobals = new HashMap<>();
    private final Stack<Set<BigInteger>> loopChangedLocalsStack = new Stack<>();
    private final Map<BigInteger, Set<BigInteger>> loopChangedLocals = new HashMap<>();
    private final LinkedList<Boolean> loopStoreMemoryStack = new LinkedList<>();
    private final Map<BigInteger, Boolean> loopStoreMemory = new HashMap<>();
    private final LinkedList<Boolean> loopGrowMemoryStack = new LinkedList<>();
    private final Map<BigInteger, Boolean> loopGrowMemory = new HashMap<>();

    private final Stack<LabelProgramCounter> labelProgramCounterStack = new Stack<>();

    private final Map<Integer, List<LabelProgramCounter>> breakDestinations = new HashMap<>();

    private final Set<Integer> reachablePCs = new HashSet<>();
    private final Map<Integer, Integer> amountOfReturnValues = new HashMap<>();

    private final WIFunction function;

    public StackLayoutHelper(WIFunction function) {
        this.function = function;
    }

    public Map<Integer, List<Integer>> getBreakDestinations() {
        return Collections.unmodifiableMap(
                breakDestinations.entrySet().stream().collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().stream().map(LabelProgramCounter::getCounter).collect(Collectors.toList())
                        )));
    }

    public Map<BigInteger, BigInteger> getAmountOfReturnValuesAsBigIntegers() {
        Map<BigInteger, BigInteger> res = new HashMap<>();
        for (var entry : amountOfReturnValues.entrySet()) {
            res.put(BigInteger.valueOf(entry.getKey()), BigInteger.valueOf(entry.getValue()));
        }

        return Collections.unmodifiableMap(res);
    }

    public Set<BigInteger> getReachablePCsAsBigIntegers() {
        return reachablePCs.stream().map(BigInteger::valueOf).collect(Collectors.toSet());
    }

    public Map<BigInteger, Boolean> getLoopCall() {
        return loopCall;
    }

    public Map<BigInteger, Set<BigInteger>> getLoopChangedGlobals() {
        return loopChangedGlobals;
    }

    public Map<BigInteger, Set<BigInteger>> getLoopChangedLocals() {
        return loopChangedLocals;
    }

    public Map<BigInteger, Boolean> getLoopStoreMemory() {
        return loopStoreMemory;
    }

    public Map<BigInteger, Boolean> getLoopGrowMemory() {
        return loopGrowMemory;
    }

    public List<BigInteger> getStackSizesAsBigIntegers() {
        return stackSizes.stream().map(BigInteger::valueOf).collect(Collectors.toList());
    }

    public Map<BigInteger, BigInteger> getIfEndsBigIntegers() {
        return Collections.unmodifiableMap(
                ifEnds.entrySet().stream().collect(
                        Collectors.toMap(
                                e -> BigInteger.valueOf(e.getKey()),
                                e -> BigInteger.valueOf(e.getValue())
                        )));
    }

    public Map<BigInteger, BigInteger> getEndStepsBigIntegers() {
        return Collections.unmodifiableMap(
                endSteps.entrySet().stream().collect(
                        Collectors.toMap(
                                e -> BigInteger.valueOf(e.getKey()),
                                e -> BigInteger.valueOf(e.getValue())
                        )));
    }

    private void recordOpcode(WICommand cmd) {
        stackSizes.add(operandStack.size());
        flattenedBody.add(cmd);

        if (cmd == null) {
            return;
        }

        if (cmd.getCode() == WCode.call || cmd.getCode() == WCode.call_indirect) {
            Collections.fill(loopCallStack, true);
        }

        if (cmd.getCode() == WCode.localSet || cmd.getCode() == WCode.localTee) {
            for (int i = 0; i < loopChangedLocalsStack.size(); ++i) {
                loopChangedLocalsStack.peek(i).add(BigInteger.valueOf(((WVariableIdx) cmd).getIndex()));
            }
        }
        if (cmd.getCode() == WCode.globalSet) {
            for (int i = 0; i < loopChangedGlobalsStack.size(); ++i) {
                loopChangedGlobalsStack.peek(i).add(BigInteger.valueOf(((WVariableIdx) cmd).getIndex()));
            }
        }
        if (WCode.isMemoryOpStore(cmd.getCode())) {
            Collections.fill(loopStoreMemoryStack, true);
        }
        if (cmd.getCode() == WCode.memory_grow) {
            Collections.fill(loopGrowMemoryStack, true);
        }
    }

    public Map<BigInteger, List<BigInteger>> getBreakDestinationsAsBigIntegers() {
        return getBreakDestinations().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> BigInteger.valueOf(e.getKey()),
                        e -> e.getValue().stream().map(BigInteger::valueOf).collect(Collectors.toList())
                ));
    }

    public Map<Integer, Integer> getEndSteps() {
        return endSteps;
    }

    private static class LabelProgramCounter {
        private int i;

        LabelProgramCounter() {
            this.i = -1;
        }

        LabelProgramCounter(int i) {
            if (i < 0) {
                throw new IllegalArgumentException();
            }
            this.i = i;
        }

        int getCounter() {
            if (i < 0) {
                throw new IllegalStateException();
            }
            return i;
        }

        void set(int i) {
            if (i < 0) {
                throw new IllegalArgumentException();
            }
            if (this.i >= 0) {
                throw new IllegalStateException();
            }
            this.i = i;
        }

        public boolean isSet() {
            return i >= 0;
        }
    }

    private static class Stack<E> {
        private final List<E> store = new ArrayList<>();

        E pop() {
            return store.remove(store.size() - 1);
        }

        void push(E elem) {
            store.add(elem);
        }

        E peek(int i) {
            return store.get(store.size() - 1 - i);
        }

        int size() {
            return store.size();
        }

        public boolean isEmpty() {
            return store.isEmpty();
        }

        public void resize(int newSize) {
            if (newSize > store.size()) {
                throw new IllegalArgumentException("Can only shrink stack when resizing.");
            }
            for (int i = store.size() - newSize; i > 0; --i) {
                store.remove(store.size() - 1);
            }
        }
    }

    private static class ControlFrame {
        private enum Opcode { BLOCK, LOOP, IF, ELSE }

        final Opcode opcode;
        final int startTypes;
        final int endTypes;
        final int height;
        boolean unreachable;
        boolean deadCode;

        private ControlFrame(Opcode opcode, int startTypes, int endTypes, int height) {
            this.opcode = opcode;
            this.startTypes = startTypes;
            this.endTypes = endTypes;
            this.height = height;
            this.unreachable = false;
            this.deadCode = false;
        }
    }

    private static int labelTypes(ControlFrame frame) {
        if(frame.opcode == ControlFrame.Opcode.LOOP) {
            return frame.startTypes;
        }
        return frame.endTypes;
    }

    private void pushOperand(boolean type) {
        operandStack.push(type);
    }

    private boolean popOperand() {
        if (operandStack.size() == controlStack.peek(0).height && (controlStack.peek(0).unreachable || controlStack.peek(0).deadCode)) {
            return false;
        }
        if (operandStack.size() == controlStack.peek(0).height) {
            throw new IllegalStateException();
        }
        return operandStack.pop();
    }

    private boolean popOperand(boolean expect) {
        boolean actual = popOperand();
        if (!actual) return expect;
        if (!expect) return actual;
        return actual;
    }

    private void pushOperands(int types) {
        for (int i = 0; i < types; ++i) {
            pushOperand(true);
        }
    }

    private void popOperands(int types) {
        for (int i = types - 1; i >= 0; --i) {
            popOperand(true);
        }
    }

    private void pushControl(ControlFrame.Opcode opcode, int in, int out) {
        controlStack.push(new ControlFrame(opcode, in, out, operandStack.size()));
    }

    private ControlFrame popControl() {
        if (controlStack.isEmpty()) {
            throw new IllegalStateException();
        }
        ControlFrame frame = controlStack.peek(0);
        popOperands(frame.endTypes);
        if (operandStack.size() != frame.height) {
            throw new IllegalStateException();
        }
        controlStack.pop();
        return frame;
    }

    private void unreachable() {
        operandStack.resize(controlStack.peek(0).height);
        controlStack.peek(0).unreachable = true;
    }

    private void deadCode() {
        operandStack.resize(controlStack.peek(0).height);
        controlStack.peek(0).deadCode = true;
    }

    public void reconstruct() {
        controlStack.push(new ControlFrame(ControlFrame.Opcode.BLOCK, 0, function.getResTypes().size(), 0));
        labelProgramCounterStack.push(new LabelProgramCounter());
        reconstruct(function.getBody());
        end();

        // Add all branch targets that are from reachable jumps in the reachable PCs set
        for (Map.Entry<Integer, List<LabelProgramCounter>> entry : breakDestinations.entrySet()) {
            if (reachablePCs.contains(entry.getKey())) {
                for (LabelProgramCounter lpc : entry.getValue()) {
                    reachablePCs.add(lpc.getCounter());
                }
            }
        }

        // Lastly, add the last end-pc that is for the entire function
        reachablePCs.add(currentProgramCounter());
    }

    // marks a given pc as reachable by adding it to the reachablePCs set.
    private boolean maybeMarkPCAsReachable(int pc) {
        if (controlStack.isEmpty() || (!controlStack.peek(0).unreachable && !controlStack.peek(0).deadCode)) {
            reachablePCs.add(pc);
            return true;
        }

        return false;
    }

    // marks a given end-pc as reachable by adding it to the reachablePCs set iff we haven't seen an unreachable op code.
    private boolean maybeMarkEndPCAsReachable(int endPc) {
        if (controlStack.isEmpty() || !controlStack.peek(0).unreachable) {
            reachablePCs.add(endPc);
            return true;
        }

        return false;
    }

    private void reconstruct(List<WICommand> commands) {
        for (var cmd : commands) {
            recordOpcode(cmd);
            maybeMarkPCAsReachable(currentProgramCounter());

            if (cmd instanceof WUnreachable) {
                unreachable();
            } else if (cmd instanceof WBlock block) {
                startBlock();
                pushControl(ControlFrame.Opcode.BLOCK, block.consumes(), block.produces());
                reconstruct(block.getBody());

                // Add the end of the block
                maybeMarkEndPCAsReachable(currentProgramCounter() + 1);

                end();
            } else if (cmd instanceof WBlockIfElse block) {
                popOperand(true);
                final WIBlock blockIf = block.getBlockIf();

                /* our code start */
                int ifStart = currentProgramCounter();
                recordOpcode(blockIf);
                maybeMarkPCAsReachable(currentProgramCounter());
                /* our code end */

                startBlock();
                pushControl(ControlFrame.Opcode.IF, workAround(blockIf.consumes()), blockIf.produces());
                reconstruct(blockIf.getBody());
                //end();
                recordEnd();

                /* our code start */
                int thenEnd = currentProgramCounter();
                maybeMarkEndPCAsReachable(thenEnd); // add the `end` pc of the then branch
                /* our code end */

                ControlFrame frame = popControl();
                final WIBlock blockElse = block.getBlockElse();

                /* our code start */
                recordOpcode(blockElse);
                maybeMarkPCAsReachable(currentProgramCounter());
                /* our code end */

                startBlock();
                pushControl(ControlFrame.Opcode.ELSE, frame.startTypes, frame.endTypes);
                reconstruct(blockElse.getBody());

                // Must call this before end(), as it pops the control frame
                maybeMarkEndPCAsReachable(currentProgramCounter() + 1);

                end();

                /* our code start */
                ifEnds.put(ifStart, currentProgramCounter() + 1);
                endSteps.put(thenEnd, currentProgramCounter() + 1);
                /* our code end */
            } else if (cmd instanceof WBlockLoop loop) {
                int pc = currentProgramCounter();
                startBlock(currentProgramCounter());
                pushControl(ControlFrame.Opcode.LOOP, loop.consumes(), loop.produces());

                loopCallStack.push(false);
                loopChangedGlobalsStack.push(new HashSet<>());
                loopChangedLocalsStack.push(new HashSet<>());
                loopStoreMemoryStack.push(false);
                loopGrowMemoryStack.push(false);
                reconstruct(loop.getBody());
                Boolean call = loopCallStack.pop();
                Set<BigInteger> changedGlobals = loopChangedGlobalsStack.pop();
                Set<BigInteger> changedLocals = loopChangedLocalsStack.pop();
                Boolean storeMemory = loopStoreMemoryStack.pop();
                Boolean growMemory = loopGrowMemoryStack.pop();

                loopCall.put(BigInteger.valueOf(pc), call);
                loopChangedGlobals.put(BigInteger.valueOf(pc), changedGlobals);
                loopChangedLocals.put(BigInteger.valueOf(pc), changedLocals);
                loopStoreMemory.put(BigInteger.valueOf(pc), storeMemory);
                loopGrowMemory.put(BigInteger.valueOf(pc), growMemory);

                // Add the end of the loop
                maybeMarkEndPCAsReachable(currentProgramCounter() + 1);

                end();
            } else if (cmd instanceof WBreak br) {
                final int n = (int) br.getLabelIdx();
                errorIf(controlStack.size() < n);

                /* our code start */
                breakDestinations.put(currentProgramCounter(), List.of(labelProgramCounterStack.peek(n)));
                amountOfReturnValues.put(currentProgramCounter(), labelTypes(controlStack.peek(n)));
                /* our code end */

                popOperands(labelTypes(controlStack.peek(n)));
                unreachable();
            } else if (cmd instanceof WBreakIf brIf) {
                final int n = (int) brIf.getLabelIdx();
                errorIf(controlStack.size() < n);

                /* our code start */
                breakDestinations.put(currentProgramCounter(), List.of(labelProgramCounterStack.peek(n)));
                amountOfReturnValues.put(currentProgramCounter(), labelTypes(controlStack.peek(n)));
                /* our code end */

                popOperand(true);
                popOperands(labelTypes(controlStack.peek(n)));
                pushOperands(labelTypes(controlStack.peek(n)));
            } else if (cmd instanceof WBreakTable brTable) {
                final int m = (int) brTable.getDefaultLabel();
                errorIf(controlStack.size() < m);
                int arity = labelTypes(controlStack.peek(m));
                for (long nl : brTable.getLabels()) {
                    final int n = (int) nl;
                    errorIf(controlStack.size() < n || labelTypes(controlStack.peek(n)) != arity);
                }

                /* our code start */
                breakDestinations.put(currentProgramCounter(),
                        Stream.concat(
                                brTable.getLabels().stream(),
                                Stream.of(brTable.getDefaultLabel())
                        ).map(i -> labelProgramCounterStack.peek((int) (long) i)).collect(Collectors.toList())
                );
                amountOfReturnValues.put(currentProgramCounter(), labelTypes(controlStack.peek(m)));
                /* our code end */

                popOperand(true);
                popOperands(labelTypes(controlStack.peek(m)));
                unreachable();
            } else if (cmd instanceof WReturn) {
                popOperands(function.getResTypes().size());
                // What comes after a return is not just dead code, but should be unreachable
                // (if return in, e.g., a block, the end of the block should not be reachable)
                unreachable();
            } else {
                //workAround
                if (cmd instanceof WCallIndirect) {
                    popOperands(cmd.consumes() + 1);
                    pushOperands(cmd.produces());
                } else {
                    popOperands(cmd.consumes());
                    pushOperands(cmd.produces());
                }
            }
        }
    }

    private int currentProgramCounter() {
        return stackSizes.size() - 1;
    }

    private int workAround(int consumes) {
        return consumes - 1;
    }


    private static void errorIf(boolean b) {
        if (b) {
            throw new IllegalStateException();
        }
    }

    private void startBlock() {
        labelProgramCounterStack.push(new LabelProgramCounter());
    }

    private void startBlock(int programCounter) {
        labelProgramCounterStack.push(new LabelProgramCounter(programCounter));
    }

    private void end() {
        endSteps.put(currentProgramCounter() + 1, currentProgramCounter() + 2);
        ControlFrame frame = popControl();
        pushOperands(frame.endTypes);

        /* our code start */
        recordEnd();
        /* our code end */
    }

    private void recordEnd() {
        // this is a hack, make better
        recordOpcode(null);
        LabelProgramCounter label = labelProgramCounterStack.pop();
        if (!label.isSet()) {
            label.set(currentProgramCounter());
        }
    }
}
