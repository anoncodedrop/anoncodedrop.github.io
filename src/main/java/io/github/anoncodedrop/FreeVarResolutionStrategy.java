package io.github.anoncodedrop;

import wien.secpriv.horst.data.Expression;
import wien.secpriv.horst.data.Proposition;
import wien.secpriv.horst.data.Rule;
import wien.secpriv.horst.execution.QuerySpecificPreprocessingStrategy;
import wien.secpriv.horst.translation.visitors.AbstractExpressionVisitor;
import wien.secpriv.horst.translation.visitors.ClauseMappingRuleVisitor;
import wien.secpriv.horst.translation.visitors.ExpressionMappingPropositionVisitor;
import wien.secpriv.horst.translation.visitors.PropositionMappingClauseVisitor;

import java.util.List;
import java.util.Optional;

public class FreeVarResolutionStrategy extends QuerySpecificPreprocessingStrategy {
    public FreeVarResolutionStrategy() {}

    private static class FreeVarExpressionVisitor extends AbstractExpressionVisitor {
        private static int freeVarCount = -1;

        @Override
        public Expression visit(Expression.AppExpression expression) {
            switch (expression.operation.name) {
                case "freeBool", "freeInt", "freeBaseValue" -> {
                    return new Expression.FreeVarExpression(expression.getType(), "??fv?" + (++freeVarCount));
                }
                default -> {
                    return super.visit(expression);
                }
            }
        }
    }

    @Override
    public Optional<List<Rule>> preprocessForQuery(List<Rule> rules, Proposition.PredicateProposition query) {
        ExpressionMappingPropositionVisitor propositionVisitor = new ExpressionMappingPropositionVisitor(new FreeVarExpressionVisitor());
        PropositionMappingClauseVisitor clauseVisitor = new PropositionMappingClauseVisitor(propositionVisitor);
        ClauseMappingRuleVisitor ruleVisitor = new ClauseMappingRuleVisitor(clauseVisitor);

        return Optional.of(rules.stream().map(r -> r.accept(ruleVisitor)).toList());
    }
}
