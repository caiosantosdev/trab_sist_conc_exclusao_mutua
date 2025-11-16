package org.cefet.sist_conc_dist.enums;

import java.util.Objects;

public enum Operation {
    REQ(1),
    GNT(2),
    REL(3);

    private final Integer enumerated;

    Operation(Integer enumerated){
        this.enumerated = enumerated;
    }

    public Integer getEnumerated(){
        return this.enumerated;
    }

    public static Operation fromNumber(Integer operation){
        for(Operation op : Operation.values()){
            if(Objects.equals(operation, op.getEnumerated())){
                return op;
            }
        }
        throw new EnumConstantNotPresentException(Operation.class, operation.toString());
    }

}
