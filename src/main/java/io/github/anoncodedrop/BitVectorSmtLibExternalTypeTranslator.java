package io.github.anoncodedrop;

import wien.secpriv.horst.translation.external.SmtLibTheoryTranslationState;
import wien.secpriv.horst.translation.external.StandardTranslateToSmtLibExternalTypeTranslator;
import wien.secpriv.horst.types.Type;

public class BitVectorSmtLibExternalTypeTranslator extends StandardTranslateToSmtLibExternalTypeTranslator {
    public BitVectorSmtLibExternalTypeTranslator(SmtLibTheoryTranslationState state) {
        super(state);
    }

    @Override
    public String delegateTranslate(Type.ExternalType type) {
        if(type.name.equals("BV64")) {
            return "(_ BitVec 64)";
        }
        return super.delegateTranslate(type);
    }
}
