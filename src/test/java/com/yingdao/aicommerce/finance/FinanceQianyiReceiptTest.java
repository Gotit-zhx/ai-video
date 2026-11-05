package com.yingdao.aicommerce.finance;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.yingdao.aicommerce.finance.FinanceData.*;

class FinanceQianyiReceiptTest {
    static Map<String,Object> source(){
        var s=FinanceQianyiEvidenceTest.receipt();s.put("customNumber","TRACK-1");s.put("orderNumber","SALE-1");return s;
    }
    static Map<String,Object> parcel(String event,long time){
        return new LinkedHashMap<>(Map.of("trackingNumber","TRACK-1","orderNumber","SALE-1","onlineOrderId","586208660231587825",
            "status","CANCEL","eventList",List.of(Map.of("event",event,"eventDate",time))));
    }
    static Map<String,Object> qualified(){
        var s=source();s.put(FinanceQianyiReceipt.FIELD,FinanceQianyiReceipt.evidence(s,List.of(parcel("Your package has been returned to the seller.",1788291000000L))));return s;
    }
    @Test void onlyExplicitReverseTerminalEvidenceCountsAndEveryIdentityMustMatch(){
        var s=source();long at=1788291000000L;
        assertEquals("SIGNED",FinanceQianyiReceipt.evidence(s,List.of(parcel("Your package has been returned to the seller in Bulacan.",at))).get("state"));
        for(String event:List.of("Package delivered to buyer.","Your package is being returned to the seller.","Return package out for delivery.",
            "Sorry, this package can no longer be delivered. Your package will be returned to the seller.")){
            var proof=parcel(event,at);proof.put("status","DELIVERED");
            assertNotEquals("SIGNED",FinanceQianyiReceipt.evidence(s,List.of(proof)).get("state"));
        }
        for(String key:List.of("trackingNumber","orderNumber","onlineOrderId")){
            var proof=parcel("Your package has been returned to the seller.",at);proof.put(key,"OTHER");
            assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(proof)).get("state"));
        }
        var stale=parcel("Your package has been returned to the seller.",at);
        var newer=parcel("Return package out for delivery.",at+1000);
        assertEquals("IN_TRANSIT",FinanceQianyiReceipt.evidence(s,List.of(stale,newer)).get("state"));
        assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(stale,parcel("Return package out for delivery.",at))).get("state"));
    }
    @Test void receiptIsMandatoryAndFinishTimeNeverSubstitutesForIt(){
        var s=source();assertTrue(FinanceQianyiReceipt.candidate(s));
        s.put("finishTime",s.get("receiveTime"));s.remove("receiveTime");
        assertFalse(FinanceQianyiReceipt.candidate(s));assertNull(FinanceQianyiEvidence.receiptDate(s,"Asia/Manila"));
        for(Object invalid:List.of(0,-1,1788291000L,"unknown","1788291000000.5",4102444800000L))
            assertEquals(0,FinanceQianyiReceipt.received(invalid));
        s=source();s.put("returnStatus","WAIT_STORAGE");assertFalse(FinanceQianyiReceipt.candidate(s));
        s=source();s.put("type","RETURN");assertFalse(FinanceQianyiReceipt.candidate(s));
        s=source();s.put("isDeleted",1);assertFalse(FinanceQianyiReceipt.candidate(s));
    }
    @Test void shopeeReturnedToSenderTerminalUsesTheSameIdentityAndLatestEventChecks(){
        var s=source();long at=1788291000000L;
        for(String event:List.of("Parcel has been returned to sender","Parcel has been returned to sender.","Package has been returned to the seller.")){
            var proof=parcel(event,at);
            assertEquals("SIGNED",FinanceQianyiReceipt.evidence(s,List.of(proof)).get("state"));
            for(String key:List.of("trackingNumber","orderNumber","onlineOrderId")){
                var other=new LinkedHashMap<>(proof);other.put(key,"OTHER");
                assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(other)).get("state"));
            }
            assertEquals("IN_TRANSIT",FinanceQianyiReceipt.evidence(s,List.of(proof,parcel("Return package out for delivery.",at+1000))).get("state"));
            assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(proof,parcel("Return package out for delivery.",at))).get("state"));
        }
        for(String event:List.of("Parcel will be returned to sender","Parcel is being returned to sender",
            "Parcel has not been returned to sender","Parcel has been returned to sorting center","Parcel delivered to buyer")){
            assertNotEquals("SIGNED",FinanceQianyiReceipt.evidence(s,List.of(parcel(event,at))).get("state"));
        }
    }
    @Test void receiptUpgradeAndDailyScanPreserveOriginalPaginationAndDoNotLoop(){
        var original=Map.<String,Object>of("from","2026-10-25","until","2026-11-02","page",7,"sourceVersion",2);
        var config=new LinkedHashMap<String,Object>(Map.of("source_version",2,"sync_start","2026-08-01"));
        var today=LocalDate.of(2026,11,3);var plan=FinanceQianyiReceipt.plan(config,original,today);
        assertEquals("2026-10-01",plan.get("from"));assertEquals("2026-11-03",plan.get("until"));
        assertTrue(Boolean.TRUE.equals(plan.get("receiptScan")));assertSame(plan,FinanceQianyiReceipt.plan(config,plan,today));
        var replay=map(plan.get("backfillResume"));var resume=map(replay.get("backfillResume"));
        assertEquals("2026-08-01",replay.get("from"));assertEquals(7,number(resume.get("page")));
        assertEquals(original.get("from"),resume.get("from"));assertEquals(original.get("until"),resume.get("until"));
        assertSame(resume,FinanceQianyiReceipt.plan(config,resume,today));
        config.put("source_version",3);config.put("last_receipt_scan_at",today.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        assertSame(original,FinanceQianyiReceipt.plan(config,original,today));
        assertNotSame(original,FinanceQianyiReceipt.plan(config,original,today.plusDays(1)));
    }
    @Test void thaiCarrierReturnEventsAreRecognizedWithoutConvertingForwardDeliveryOrFutureReturns(){
        var s=source();long at=1788291000000L;
        var terminal=parcel("ผู้ส่งได้รับพัสดุคืนเรียบร้อยแล้ว  ",at);terminal.put("status","UNDELIVERABLE");
        assertEquals("SIGNED",FinanceQianyiReceipt.evidence(s,List.of(terminal)).get("state"));
        for(String text:List.of("บริษัทขนส่งกำลังนำส่งพัสดุคืนผู้ส่ง","พัสดุตีกลับไปยังผู้ส่ง อยู่ระหว่างการขนส่ง: SOCW",
                "บริษัทขนส่งจัดส่งพัสดุไม่สำเร็จและจะถูกส่งกลับไปยังผู้ส่ง")){
            assertEquals("IN_TRANSIT",FinanceQianyiReceipt.evidence(s,List.of(terminal,parcel(text,at+1000))).get("state"));
            assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(terminal,parcel(text,at))).get("state"));
        }
        for(String text:List.of("ผู้รับได้รับพัสดุเรียบร้อยแล้ว","ผู้ส่งยังไม่ได้รับพัสดุคืน","ผู้ส่งจะได้รับพัสดุคืนเรียบร้อยแล้ว",
                "บริษัทขนส่งเข้ารับพัสดุเรียบร้อยแล้ว")){
            assertNotEquals("SIGNED",FinanceQianyiReceipt.evidence(s,List.of(parcel(text,at))).get("state"));
        }
        for(String key:List.of("trackingNumber","orderNumber","onlineOrderId")){
            var wrong=new LinkedHashMap<>(terminal);wrong.put(key,"OTHER");
            assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(wrong)).get("state"));
        }
        assertEquals("UNKNOWN",FinanceQianyiReceipt.evidence(s,List.of(terminal,parcel("ผู้ส่งยังไม่ได้รับพัสดุคืน",at+1000))).get("state"));
    }
    @Test void listAndDetailLabelsUseTheSameMappingAsExport(){
        for(String state:List.of("SIGNED","IN_TRANSIT","UNKNOWN","")){
            var row=new LinkedHashMap<String,Object>(Map.of("return_receipt_state",state));
            FinanceQianyiReturns.attachReceiptLabel(row);
            assertEquals(FinanceQianyiReturns.receiptLabel(state),row.get("return_receipt_label"));
            assertNotEquals("—",row.get("return_receipt_label"));
            assertNotEquals("待确认",row.get("return_receipt_label"));
        }
    }
    @Test void completedReceiptFlagsAreNotMistakenForHistoricalCursors(){
        var config=Map.<String,Object>of("source_version",3,"sync_start","2026-08-01",
                "last_success_at",Instant.parse("2026-11-04T06:36:56Z"));
        var flags=Map.<String,Object>of("sourceVersion",3,"sourcesChanged",true);
        assertTrue(FinanceQianyiWorker.markerOnly(flags));
        var resumed=FinanceQianyiWorker.initializeCheckpoint(config,flags,LocalDate.of(2026,11,5));
        assertEquals("2026-11-02",resumed.get("from"));assertEquals("2026-11-05",resumed.get("until"));
        assertEquals(1,number(resumed.get("page")));assertEquals(true,resumed.get("sourcesChanged"));
        assertEquals(flags,Map.of("sourceVersion",3,"sourcesChanged",true));
    }
    @Test void existingOrMalformedRealCursorIsNeverResetByMarkerRecovery(){
        var config=Map.<String,Object>of("source_version",3,"sync_start","2026-08-01");
        for(var saved:List.of(Map.<String,Object>of("from","2026-10-01","until","2026-10-31","page",8,"sourceVersion",3),
                Map.<String,Object>of("page",8,"sourcesChanged",true))){
            assertFalse(FinanceQianyiWorker.markerOnly(saved));
            assertEquals(saved,FinanceQianyiWorker.initializeCheckpoint(config,saved,LocalDate.of(2026,11,5)));
        }
    }
}
