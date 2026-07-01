package com.ubs.pesubapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ubs.pesubapi.entity.LpMaster;

public record LpMasterDto(
        Integer id,
        String  name,
        String  parent,
        boolean spv,
        boolean hq,
        @JsonProperty("investor_type")   String investorType,
        @JsonProperty("inst_vs_hnw")     String instVsHnw,
        @JsonProperty("region_location") String regionLocation,
        boolean ig,
        String  cls,
        String  sp,
        String  mdy,
        String  fitch,
        String  aum,
        String  nav,
        String  pension,
        String  pensionFunded,
        String  rate,
        String  ubsConc,
        String  notes
) {
    public static LpMasterDto from(LpMaster m) {
        return new LpMasterDto(
                m.getId(),
                m.getInvestorName(),
                m.getParent(),
                m.isSpv(),
                m.isHighQty(),
                m.getInvestorType(),
                m.getInstVsHnw(),
                m.getRegionLocation(),
                m.isIg(),
                m.getUbsClassification(),
                m.getSp(),
                m.getMdy(),
                m.getFitch(),
                m.getAum(),
                m.getNav(),
                m.getPension(),
                m.getPensionFunded(),
                m.getUbsDefaultAdvRate(),
                m.getUbsDefaultConcLimit(),
                m.getNotes()
        );
    }
}