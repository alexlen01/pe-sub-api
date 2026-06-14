package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.Lp;

import java.time.LocalDateTime;

public record LpDto(
    Integer       id,
    Integer       facilityId,
    String        name,
    String        parent,
    boolean       spv,
    boolean       hq,
    String        type,
    String        region,
    boolean       ig,
    String        cls,
    String        clsTag,
    String        sp,
    String        mdy,
    String        fitch,
    String        aum,
    String        nav,
    String        pension,
    String        pensionFunded,
    String        capCommit,
    String        pctCapCommit,
    String        calledCap,
    String        uc,
    String        pctUncalled,
    String        pctCalled,
    String        agentConc,
    String        ubsConc,
    String        agentRate,
    String        abb,
    boolean       inc,
    boolean       rcl,
    boolean       tf,
    String        notes,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static LpDto from(Lp lp) {
        return new LpDto(
            lp.getId(), lp.getFacilityId(), lp.getInvestorName(), lp.getParent(),
            lp.isSpv(), lp.isHighQty(), lp.getInvType(), lp.getRegion(), lp.isIg(),
            lp.getCls(), lp.getClsTag(), lp.getSp(), lp.getMdy(), lp.getFitch(),
            lp.getAum(), lp.getNav(), lp.getPension(), lp.getPensionFunded(),
            lp.getCapCommit(), lp.getPctCapCommit(), lp.getCalledCap(),
            lp.getUc(), lp.getPctUncalled(), lp.getPctCalled(),
            lp.getAgentConc(), lp.getUbsConc(), lp.getAgentRate(), lp.getAbb(),
            lp.isInc(), lp.isRcl(), lp.isTf(), lp.getNotes(),
            lp.getCreatedAt(), lp.getUpdatedAt());
    }
}
