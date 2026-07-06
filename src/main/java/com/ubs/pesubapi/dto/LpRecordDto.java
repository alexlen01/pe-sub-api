package com.ubs.pesubapi.dto;

import com.ubs.pesubapi.entity.LpRecord;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record LpRecordDto(
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
    String        agentClsSource,
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
    public static LpRecordDto from(LpRecord lpRecord) {
        return new LpRecordDto(
            lpRecord.getId(), lpRecord.getFacilityId(), lpRecord.getInvestorName(), lpRecord.getParent(),
            lpRecord.isSpv(), lpRecord.isHighQty(), lpRecord.getInvestorType(), lpRecord.getInstVsHnw(), lpRecord.getRegionLocation(), lpRecord.isIg(),
            lpRecord.getAgentCls(), lpRecord.getAgentClsSource(), lpRecord.getCls(), lpRecord.getClsTag(), lpRecord.getSp(), lpRecord.getMdy(), lpRecord.getFitch(),
            lpRecord.getAum(), lpRecord.getNav(),
            lpRecord.getPension(), lpRecord.getPensionFunded(),
            lpRecord.getCapCommit(), lpRecord.getPctCapCommit(), lpRecord.getCalledCap(),
            lpRecord.getUc(), lpRecord.getPctUncalled(), lpRecord.getPctCalled(),
            lpRecord.getAgentConc(), lpRecord.getUbsConc(), lpRecord.getUbsRate(), lpRecord.getAgentRate(),
            lpRecord.getAgentExcessConc(), lpRecord.getUbsExcessConc(), lpRecord.getAbb(), lpRecord.getUbb(),
            lpRecord.isInc(), lpRecord.isRcl(), lpRecord.isTf(), lpRecord.getNotes(),
            lpRecord.getCreatedAt(), lpRecord.getUpdatedAt());
    }
}
