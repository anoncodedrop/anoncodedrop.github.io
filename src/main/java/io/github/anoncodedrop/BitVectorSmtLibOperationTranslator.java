package io.github.anoncodedrop;

import org.apache.commons.lang3.StringUtils;
import wien.secpriv.horst.data.Expression;
import wien.secpriv.horst.translation.external.SmtLibTheoryTranslationState;
import wien.secpriv.horst.translation.external.StandardTranslateToSmtLibExternalOperationTranslator;

import java.util.Set;

public class BitVectorSmtLibOperationTranslator extends StandardTranslateToSmtLibExternalOperationTranslator {
    public BitVectorSmtLibOperationTranslator(SmtLibTheoryTranslationState state) {
        super(state);
    }

    private static final Set<String> binaryOperations = Set.of(
            "bvadd", "bvsub", "bvmul", "bvsdiv", "bvudiv", "bvsrem", "bvurem",
            "bvor", "bvand", "bvxor");
    private static final Set<String> comparisonOperations = Set.of(
            "bvuge", "bvsgt", "bvsge", "bvugt", "bvult", "bvslt", "bvsle", "bvule");
    private static final Set<String> reversedBinaryOperations = Set.of("bvshl", "bvlshr", "bvashr");

    @Override
    public Void delegateTranslate(Expression.AppExpression expression, Expression.Visitor<Void> callingVisitor) {
        StringBuilder sb = state.stringBuilder;
        switch (expression.operation.name) {
            case "bv2int" -> {
                sb.append("(");
                sb.append("bv2nat ");
                expression.expressions.get(0).accept(callingVisitor);
                sb.append(")");
            }
            case "cint2bv" -> {
                long v = ((Expression.IntConst) expression.parameters.get(1)).value.longValue();
                sb.append("#x");
                sb.append(StringUtils.leftPad(Long.toHexString(v), 16, "0"));
            }
            case "int2bv" -> {
                sb.append("(");
                sb.append("(_ int2bv ");
                sb.append("64");
                sb.append(") ");
                expression.expressions.get(0).accept(callingVisitor);
                sb.append(")");
            }
            case "bvsrem" -> {
                String name = "bvurem";
                sb.append("(");
                sb.append(name);
                sb.append(" ");
                expression.expressions.get(0).accept(callingVisitor);
                sb.append(" ");
                expression.expressions.get(1).accept(callingVisitor);
                sb.append(")");
            }
            case "bvsdiv" -> {
                String name = "bvudiv";
                sb.append("(");
                sb.append(name);
                sb.append(" ");
                expression.expressions.get(0).accept(callingVisitor);
                sb.append(" ");
                expression.expressions.get(1).accept(callingVisitor);
                sb.append(")");
            }
            case "__dummy__BV64" -> {
                sb.append("#x");
                sb.append(StringUtils.leftPad(Long.toHexString(0), 16, "0"));
            }
            default -> {
                String name = expression.operation.name;
                if (name.equals("bvneg")) {
                    int bitWidth = ((Expression.IntConst) expression.parameters.get(0)).value.intValueExact();
                    String extractOpen = "";
                    String extendOpen = "";
                    String close = "";

                    if (bitWidth < 64) {
                        extractOpen = "((_ extract " + (64 - bitWidth - 1) + "  0) ";
                        extendOpen = "((_ zero_extend " + (64 - bitWidth) + ") ";
                        close = ")";
                    } else if (bitWidth > 64) {
                        throw new IllegalStateException("Only bitwidths smaller than " + 64 + " are allowed!");
                    }

                    sb.append(extendOpen);
                    sb.append("(");
                    sb.append(expression.operation.name);
                    sb.append(" ");
                    sb.append(extractOpen);
                    expression.expressions.get(0).accept(callingVisitor);
                    sb.append(close);
                    sb.append(")");
                    sb.append(close);
                } else if (binaryOperations.contains(name)) {
                    binaryOperation(expression, callingVisitor, sb);
                } else if (reversedBinaryOperations.contains(name)) {
                    reversedBinaryOperation(expression, callingVisitor, sb);
                } else if (comparisonOperations.contains(name)) {
                    String extractOpen = "";
                    String close = "";
                    int bitWidth = ((Expression.IntConst) expression.parameters.get(0)).value.intValueExact();

                    if (bitWidth < 64) {
                        extractOpen = "((_ extract " + (64 - bitWidth - 1) + "  0) ";
                        close = ")";
                    } else if (bitWidth > 64) {
                        throw new IllegalStateException("Only bitwidths smaller than " + 64 + " are allowed!");
                    }

                    sb.append("(");
                    sb.append(expression.operation.name);
                    sb.append(" ");
                    sb.append(extractOpen);
                    expression.expressions.get(0).accept(callingVisitor);
                    sb.append(close);
                    sb.append(" ");
                    sb.append(extractOpen);
                    expression.expressions.get(1).accept(callingVisitor);
                    sb.append(close);
                    sb.append(")");
                } else if (name.equals("bvextends")) {
                    String extractOpen = "";
                    String extendOpen = "";
                    String close = "";
                    int m = ((Expression.IntConst) expression.parameters.get(0)).value.intValueExact();
                    int n = ((Expression.IntConst) expression.parameters.get(1)).value.intValueExact();

                    final int bitWidth = n;

                    if (bitWidth < 64) {
                        extendOpen = "((_ zero_extend " + (64 - bitWidth) + ") ";
                        extractOpen = "((_ extract " + (64 - bitWidth - 1) + "  0) ";
                        close = ")";
                    } else if (bitWidth > 64) {
                        throw new IllegalStateException("Only bitwidths smaller than " + 64 + " are allowed!");
                    }

                    if (n > 64 || m > 64) {
                        throw new IllegalStateException("Only ns smaller than " + 64 + " are allowed!");
                    }
                    //TODO this could be optimised

                    sb.append(extendOpen);
                    sb.append(extractOpen);

                    sb.append("(");
                    sb.append("(_ sign_extend ");
                    sb.append(64 - m);
                    sb.append(") ");
                    sb.append("((_ extract ").append(m - 1).append("  0) ");
                    expression.expressions.get(0).accept(callingVisitor);
                    sb.append(")");
                    sb.append(")");

                    sb.append(close);
                    sb.append(close);
                } else if (name.equals("bvextendu")) {
                    expression.expressions.get(0).accept(callingVisitor);
                } else if (name.equals("bvextract")) {
                    String extractOpen = "";
                    String extendOpen = "";
                    String close = "";
                    int bitWidth = ((Expression.IntConst) expression.parameters.get(0)).value.intValueExact();

                    int n = ((Expression.IntConst) expression.parameters.get(1)).value.intValueExact();

//                    if (bitWidth == 64 && n < bitWidth) {
//                    if (bitWidth == 64) {
                        extractOpen = "((_ extract " + (n - 1) + "  0) ";
                        extendOpen = "((_ zero_extend " + (64 - n) + ") ";
                        close = ")";
//                    }

                    sb.append(extendOpen);
                    sb.append(extractOpen);
                    expression.expressions.get(0).accept(callingVisitor);
                    sb.append(close);
                    sb.append(close);
                } else {
                    super.delegateTranslate(expression, callingVisitor);
                }
            }
        }
        return null;
    }

    private void binaryOperation(Expression.AppExpression expression, Expression.Visitor<Void> callingVisitor, StringBuilder sb) {
        generalBinaryOperation(expression, callingVisitor, sb,
                () -> expression.expressions.get(0).accept(callingVisitor),
                () -> expression.expressions.get(1).accept(callingVisitor)
        );
    }

    private void reversedBinaryOperation(Expression.AppExpression expression, Expression.Visitor<Void> callingVisitor, StringBuilder sb) {
        generalBinaryOperation(expression, callingVisitor, sb,
                () -> expression.expressions.get(0).accept(callingVisitor),
                () -> expression.expressions.get(1).accept(callingVisitor)
        );
    }

    private void generalBinaryOperation(Expression.AppExpression expression, Expression.Visitor<Void> callingVisitor, StringBuilder sb, Runnable child1, Runnable child2) {
        int bitWidth = ((Expression.IntConst) expression.parameters.get(0)).value.intValueExact();
        String extractOpen = "";
        String extendOpen = "";
        String close = "";

        if (bitWidth < 64) {
            extractOpen = "((_ extract " + (64 - bitWidth - 1) + "  0) ";
            extendOpen = "((_ zero_extend " + (64 - bitWidth) + ") ";
            close = ")";
        } else if (bitWidth > 64) {
            throw new IllegalStateException("Only bitwidths smaller than " + 64 + " are allowed!");
        }

        sb.append(extendOpen);
        sb.append("(");
        sb.append(expression.operation.name);
        sb.append(" ");
        sb.append(extractOpen);
        child1.run();
        sb.append(close);
        sb.append(" ");
        sb.append(extractOpen);
        child2.run();
        sb.append(close);
        sb.append(")");
        sb.append(close);
    }
}
