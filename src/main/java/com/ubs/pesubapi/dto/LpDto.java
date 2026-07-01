package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.Lp;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record LpDto(
    Integer       id,
    Integer       facilityId,
    String        name,
    String        parent,
    boolean       spv,
    boolean       hq,
    @JsonProperty("investor_type")
    String        investorType,
    @JsonProperty("inst_vs_hnw")
    String        instVsHnw,
    @JsonProperty("region_location")
    String        regionLocation,
    boolean       ig,
    String        agentCls,
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
    String        rate,
    String        agentRate,
    String        agentExcessConc,
    String        ubsExcessConc,
    String        abb,
    String        ubb,
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
            lp.isSpv(), lp.isHighQty(), lp.getInvestorType(), lp.getInstVsHnw(), lp.getRegionLocation(), lp.isIg(),
            lp.getAgentCls(), lp.getCls(), lp.getClsTag(), lp.getSp(), lp.getMdy(), lp.getFitch(),
            lp.getAum(), lp.getNav(),
            lp.getPension(), lp.getPensionFunded(),
            lp.getCapCommit(), lp.getPctCapCommit(), lp.getCalledCap(),
            lp.getUc(), lp.getPctUncalled(), lp.getPctCalled(),
            lp.getAgentConc(), lp.getUbsConc(), lp.getUbsRate(), lp.getAgentRate(),
            lp.getAgentExcessConc(), lp.getUbsExcessConc(), lp.getAbb(), lp.getUbb(),
            lp.isInc(), lp.isRcl(), lp.isTf(), lp.getNotes(),
            lp.getCreatedAt(), lp.getUpdatedAt());
    }
}
