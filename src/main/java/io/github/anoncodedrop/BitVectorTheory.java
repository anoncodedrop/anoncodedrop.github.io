package io.github.anoncodedrop;

import wien.secpriv.horst.data.Expression;
import wien.secpriv.horst.translation.ExternalOperationTranslator;
import wien.secpriv.horst.translation.ExternalTypeTranslator;
import wien.secpriv.horst.translation.external.ExternalTypeDefiner;
import wien.secpriv.horst.translation.external.SmtLibTheory;
import wien.secpriv.horst.translation.external.SmtLibTheoryTranslationState;
import wien.secpriv.horst.translation.external.StandardTranslateToSmtLibExternalTypeDefiner;
import wien.secpriv.horst.visitors.ReadableOperationScope;

public class BitVectorTheory implements SmtLibTheory {
    @Override
    public ExternalTypeTranslator<String> getSmtLibTypeTranslator(SmtLibTheoryTranslationState state) {
        return new BitVectorSmtLibExternalTypeTranslator(state);
    }

    @Override
    public ExternalOperationTranslator<Void> getSmtLibOperationTranslator(SmtLibTheoryTranslationState state) {
        return new BitVectorSmtLibOperationTranslator(state);
    }

    @Override
    public ExternalTypeDefiner<String> getSmtLibTypeDefiner(SmtLibTheoryTranslationState state) {
        return new StandardTranslateToSmtLibExternalTypeDefiner(state);
    }

    @Override
    public ExternalOperationTranslator<Expression> getConstantFoldingTranslator(ReadableOperationScope readableOperationScope) {
        return new BitVectorConstantFoldingExternalOperationTranslator();
    }
}
