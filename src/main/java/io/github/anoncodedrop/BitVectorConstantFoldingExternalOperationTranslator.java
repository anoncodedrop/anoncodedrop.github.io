package io.github.anoncodedrop;

import wien.secpriv.horst.data.Expression;
import wien.secpriv.horst.data.Operation;
import wien.secpriv.horst.tools.ExpressionTools;
import wien.secpriv.horst.translation.external.StandardSmtConstantFoldingExternalOperationTranslator;
import wien.secpriv.horst.types.Type;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static wien.secpriv.horst.tools.ExpressionTools.boolConst;
import static wien.secpriv.horst.tools.ExpressionTools.intConst;

public class BitVectorConstantFoldingExternalOperationTranslator extends StandardSmtConstantFoldingExternalOperationTranslator {
    private final Operation cint2bv = new Operation.ExternalOperation("cint2bv",
            List.of(new Expression.ParVarExpression(Type.Integer, "!bw"), new Expression.ParVarExpression(Type.Integer, "!v")),
            List.of(),
            new Type.ExternalType("BV64")
    );

    @Override
    public Expression translate(Expression.AppExpression expression, Expression.Visitor<Expression> visitor) {
        List<Expression> visitedParameters = expression.parameters.stream().map(e -> e.accept(visitor)).toList();
        List<Expression> visitedArguments = expression.expressions.stream().map(e -> e.accept(visitor)).toList();

        Optional<Expression> makeJavasTypeSystemHappy = switch (expression.operation.name) {
            case "bvadd" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvadd32, this::bvadd64);
            case "bvsub" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvsub32, this::bvsub64);
            case "bvmul" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvmul32, this::bvmul64);
            case "bvudiv" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvudiv32, this::bvudiv64);

            case "bvurem" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvurem32, this::bvurem64);
            case "bvand" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvand, this::bvand);
            case "bvor" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvor, this::bvor);
            case "bvxor" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvxor, this::bvxor);
            case "bvshl" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvshl32, this::bvshl64);
            case "bvlshr" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvlshr32, this::bvlshr64);
            case "bvashr" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvashr32, this::bvashr64);

            case "bvextendu" -> bvextendu(visitedParameters.get(0), visitedParameters.get(1), visitedArguments.get(0));
            case "bvextends" -> bvextends(visitedParameters.get(0), visitedParameters.get(1), visitedArguments.get(0));
            case "bvextract" -> bvextract(visitedParameters.get(0), visitedParameters.get(1), visitedArguments.get(0));

            case "bvneg" -> applyFunction(visitedParameters.get(0), visitedArguments.get(0), this::bvneg32, this::bvneg64);

            case "bvsgt" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvsgt32, this::bvsgt64);
            case "bvslt" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvslt32, this::bvslt64);
            case "bvsge" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvsge32, this::bvsge64);
            case "bvsle" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvsle32, this::bvsle64);

            case "bvugt" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvugt32, this::bvugt64);
            case "bvult" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvult32, this::bvult64);
            case "bvuge" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvuge32, this::bvuge64);
            case "bvule" -> applyComparison(visitedParameters.get(0), visitedArguments.get(0), visitedArguments.get(1), this::bvule32, this::bvule64);

            case "bv2int" -> bv2int(visitedArguments.get(0));
            case "int2bv" -> int2bv(visitedParameters.get(0), visitedArguments.get(0));

            default -> Optional.empty();
        };

        return makeJavasTypeSystemHappy.orElseGet(() -> new Expression.AppExpression(expression.operation, visitedParameters, visitedArguments));
    }


    @Override
    public Expression translateEquality(Expression.ComparisonExpression expression, Expression.Visitor<Expression> callingVisitor) {
        Expression child1 = expression.expression1.accept(callingVisitor);
        Expression child2 = expression.expression2.accept(callingVisitor);

        return getConst(child1).flatMap(
                bix -> getConst(child2).map(
                        biy -> (Expression) boolConst((expression.operation == Expression.CompOperation.EQ) == bix.equals(biy))
                )).orElseGet(() -> super.translateEquality(expression, callingVisitor));
    }

    private Optional<Expression> bvextract(Expression m, Expression n, Expression x) {
        return getConst(x).flatMap(cx -> {
                    int cm = ((Expression.IntConst) m).value.intValueExact();
                    long cn = ((Expression.IntConst) n).value.longValueExact();
                    long mask = (cn == 64 ? 0 : (1L << cn)) - 1;

                    return Optional.of(cint2bv(cm, cx.longValue() & mask));
                }
        );
    }

    private Optional<Expression> int2bv(Expression bitWidth, Expression x) {
        if (x instanceof Expression.IntConst intConst) {
            return Optional.of(cint2bv(((Expression.IntConst) bitWidth).value.intValueExact(), intConst.value.longValue()));
        }
        return Optional.empty();
    }

    private Optional<Expression> bv2int(Expression expression) {
        return getConst(expression).map(ExpressionTools::intConst);
    }

    private boolean bvsgt32(long a, long b) {
        return (int) a > (int) b;
    }

    private boolean bvsgt64(long a, long b) {
        return a > b;
    }

    private boolean bvslt32(long a, long b) {
        return (int) a < (int) b;
    }

    private boolean bvslt64(long a, long b) {
        return a < b;
    }

    private boolean bvsge32(long a, long b) {
        return (int) a >= (int) b;
    }

    private boolean bvsge64(long a, long b) {
        return a >= b;
    }

    private boolean bvsle32(long a, long b) {
        return (int) a <= (int) b;
    }

    private boolean bvsle64(long a, long b) {
        return a <= b;
    }

    private boolean bvugt32(long a, long b) {
        return Integer.compareUnsigned((int) a, (int) b) > 0;
    }

    private boolean bvugt64(long a, long b) {
        return Long.compareUnsigned(a, b) > 0;
    }

    private boolean bvult32(long a, long b) {
        return Integer.compareUnsigned((int) a, (int) b) < 0;
    }

    private boolean bvult64(long a, long b) {
        return Long.compareUnsigned(a, b) < 0;
    }

    private boolean bvuge32(long a, long b) {
        return Integer.compareUnsigned((int) a, (int) b) >= 0;
    }

    private boolean bvuge64(long a, long b) {
        return Long.compareUnsigned(a, b) >= 0;
    }

    private boolean bvule32(long a, long b) {
        return Integer.compareUnsigned((int) a, (int) b) <= 0;
    }

    private boolean bvule64(long a, long b) {
        return Long.compareUnsigned(a, b) <= 0;
    }

    private long bvneg32(long a) {
        return -a & 0x00000000FFFFFFFFL;
    }

    private long bvneg64(long a) {
        return -a;
    }

    private long bvshl32(long a, long b) {
        if (Integer.compareUnsigned((int) b, 32) >= 0) {
            return 0;
        }

        return Integer.toUnsignedLong((int) a << (int) b);
    }

    private long bvshl64(long a, long b) {
        if (b < 0 || b >= 64) {
            return 0;
        }

        return a << b;
    }

    private long bvlshr32(long a, long b) {
        if (Integer.compareUnsigned((int) b, 32) >= 0) {
            return 0;
        }

        return Integer.toUnsignedLong((int) a >>> (int) b);
    }

    private long bvlshr64(long a, long b) {
        if (b < 0 || b >= 64) {
            return 0;
        }
        return a >>> b;
    }

    private long bvashr32(long a, long b) {
        if (Integer.compareUnsigned((int) b, 32) >= 0) {
            return ((int) a < 0) ? 0xFFFFFFFFL : 0;
        }

        return Integer.toUnsignedLong((int) a >> (int) b);
    }

    private long bvashr64(long a, long b) {
        if (b < 0 || b >= 64) {
            return (a < 0) ? 0xFFFFFFFFFFFFFFFFL : 0;
        }
        return a >> b;
    }

    private long bvand(long a, long b) {
        return a & b;
    }

    private long bvor(long a, long b) {
        return a | b;
    }

    private long bvxor(long a, long b) {
        return a ^ b;
    }

    private long bvsub32(long a, long b) {
        return Integer.toUnsignedLong((int) a - (int) b);
    }

    private long bvsub64(long a, long b) {
        return a - b;
    }

    private long bvurem32(long a, long b) {
        return Integer.toUnsignedLong(Integer.remainderUnsigned((int) a, (int) b));
    }

    private long bvurem64(long a, long b) {
        return Long.remainderUnsigned(a, b);
    }

    private long bvudiv32(long a, long b) {
        return Integer.toUnsignedLong(Integer.divideUnsigned((int) a, (int) b));
    }

    private long bvudiv64(long a, long b) {
        return Long.divideUnsigned(a, b);
    }

    private long bvadd32(long a, long b) {
        return Integer.toUnsignedLong((int) a + (int) b);
    }

    private long bvadd64(long a, long b) {
        return a + b;
    }

    private long bvmul32(long a, long b) {
        return Integer.toUnsignedLong((int) a * (int) b);
    }

    private long bvmul64(long a, long b) {
        return a * b;
    }

    private Optional<Expression> applyFunction(Expression bitWidth, Expression a, Function<Long, Long> function32, Function<Long, Long> function64) {
        int bitWidthAsInt = ((Expression.IntConst) bitWidth).value.intValueExact();
        Function<Long, Long> function = switch (bitWidthAsInt) {
            case 32 -> function32;
            case 64 -> function64;
            default -> throw new IllegalArgumentException("Unsupported bitwidth " + bitWidthAsInt);
        };

        return getConst(a).flatMap(ca -> Optional.of(cint2bv(bitWidthAsInt, function.apply(ca.longValue()))));
    }

    private Optional<Expression> applyFunction(Expression bitWidth, Expression a, Expression b, BiFunction<Long, Long, Long> function32, BiFunction<Long, Long, Long> function64) {
        int bitWidthAsInt = ((Expression.IntConst) bitWidth).value.intValueExact();
        BiFunction<Long, Long, Long> function = switch (bitWidthAsInt) {
            case 32 -> function32;
            case 64 -> function64;
            default -> throw new IllegalArgumentException("Unsupported bitwidth " + bitWidthAsInt);
        };

        return getConst(a).flatMap(
                ca -> getConst(b).flatMap(
                        cb -> Optional.of(cint2bv(bitWidthAsInt, function.apply(ca.longValue(), cb.longValue())))));
    }

    private Optional<Expression> bvextendu(Expression m, Expression n, Expression x) {
        BigInteger cm = ((Expression.IntConst) m).value;
        BigInteger cn = ((Expression.IntConst) n).value;

        return getConst(x).flatMap(cx -> {
            return Optional.of(cint2bv(64, cx.longValue()));
        });
    }

    private Optional<Expression> bvextends(Expression m, Expression n, Expression x) {
        int cm = ((Expression.IntConst) m).value.intValueExact();
        int cn = ((Expression.IntConst) n).value.intValueExact();

        if (cm > 64 || cn > 64) {
            throw new IllegalArgumentException();
        }
        if (cm > cn) {
            throw new IllegalArgumentException();
        }

        return getConst(x).flatMap(cx -> {
            long mask = (cn == 64 ? 0 : (1L << cn)) - 1;
            long smask = ~((1L << cm) - 1);
            long scheck = 1L << (cm - 1);

            long result;
            long lx = cx.longValue();
            if ((scheck & lx) != 0) {
                result = (lx | smask) & mask;
            } else {
                result = (lx & (~smask)) & mask;
            }

            return Optional.of(cint2bv(64, result));
        });
    }

    private Optional<Expression> applyComparison(Expression bitWidth, Expression a, Expression b, BiPredicate<Long, Long> function32, BiPredicate<Long, Long> function64) {
        int bitWidthAsInt = ((Expression.IntConst) bitWidth).value.intValueExact();
        BiPredicate<Long, Long> function = switch (bitWidthAsInt) {
            case 32 -> function32;
            case 64 -> function64;
            default -> throw new IllegalArgumentException("Unsupported bitwidth " + bitWidthAsInt);
        };

        return getConst(a).flatMap(
                ca -> getConst(b).flatMap(
                        cb -> Optional.of(boolConst(function.test(ca.longValue(), cb.longValue())))));
    }

    private Expression cint2bv(int bitWidth, long value) {
        BigInteger unsignedBigInteger;
        if (bitWidth == 64 && value < 0) {
            unsignedBigInteger = BigInteger.valueOf(value >>> 32).shiftLeft(32).or(BigInteger.valueOf(value & 0xFFFFFFFFL));
        } else {
            unsignedBigInteger = BigInteger.valueOf(value);
        }

        return new Expression.AppExpression(cint2bv, List.of(intConst(bitWidth), intConst(unsignedBigInteger)), Collections.emptyList());
    }

    private Optional<BigInteger> getConst(Expression a) {
        if (a instanceof Expression.AppExpression app) {
            if (app.operation.name.equals("cint2bv")) {
                return Optional.of(((Expression.IntConst) app.parameters.get(1)).value);
            }
        }
        return Optional.empty();
    }
}
