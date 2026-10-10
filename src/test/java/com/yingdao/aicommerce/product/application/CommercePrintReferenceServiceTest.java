package com.yingdao.aicommerce.product.application;

import com.yingdao.aicommerce.auth.PlatformPrincipal;
import com.yingdao.aicommerce.common.exception.BusinessException;
import com.yingdao.aicommerce.saas.application.ModelConfigService;
import com.yingdao.aicommerce.shop.infrastructure.MarketplaceImageDownloader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommercePrintReferenceServiceTest {
    @TempDir Path temp;
    private final PlatformPrincipal user = new PlatformPrincipal("user", "org", "company", "u", "u", "", true, List.of(), List.of("product:edit", "product:view"));
    private final ModelConfigService.RuntimeModelConnection model = new ModelConfigService.RuntimeModelConnection("model-config", "https://model.invalid", "image-model", "OPENAI", "AUTO", "private-key");
    private final ObjectMapper json = new ObjectMapper();

    @Test void returnsQueuedWithoutCallingModelAndReusesSameSourceAcrossRequestIds() throws Exception {
        var h = harness(); String source = h.upload();
        var first = h.service.start(new CommercePrintReferenceService.Request(source, false, "request-one"), user);
        var duplicate = h.service.start(new CommercePrintReferenceService.Request(source, false, "request-two"), user);
        assertEquals("QUEUED", first.status()); assertEquals(first.id(), duplicate.id()); assertEquals(first.id(), first.referenceSetId());
        assertEquals(source, first.originalImageId()); assertTrue(first.baseImages().isEmpty());
        assertEquals(1, h.jobs.rows.size()); verifyNoInteractions(h.gateway);
        assertFalse(json.writeValueAsString(first).contains("private-key"));
        assertThrows(BusinessException.class, () -> h.service.start(new CommercePrintReferenceService.Request("c".repeat(64), false, "request-two"), user), "reused source aliases must still bind their request payload");
    }

    @Test void onePersistedAttemptBecomesFourViewsFromExactlyOneSheetAndSurvivesRestart() throws Exception {
        var h = harness(); String source = h.upload(); var view = h.service.start(new CommercePrintReferenceService.Request(source, false, "request-one"), user);
        when(h.gateway.generateArtwork(anyString(), eq(view.id()), any(), anyList())).thenAnswer(call -> {
            assertEquals("SUBMITTING", h.jobs.find("org", view.id()).status(), "claim must be durable before provider call");
            var refs = (List<CommerceDesignImageGateway.Reference>) call.getArgument(3);
            assertEquals(1, refs.size()); assertArrayEquals(image(128, Color.ORANGE), Base64.getDecoder().decode(refs.getFirst().dataBase64()));
            return artifact(sheet());
        });
        h.service.process(new CommercePrintReferenceRepository.Work("org", view.id()), "first-token");
        assertEquals("RESULT_RECEIVED", h.jobs.find("org", view.id()).status());
        // A new service instance resumes the saved response, with no second model submission.
        var resumed = new CommercePrintReferenceService(h.jobs, h.models, h.gateway, h.archive, h.downloader, json);
        resumed.process(new CommercePrintReferenceRepository.Work("org", view.id()), "second-token");
        var ready = resumed.get(view.id(), user);
        assertEquals("READY", ready.status()); assertEquals(source, ready.originalImageId()); assertEquals(4, ready.baseImages().size());
        assertNotEquals(source, ready.baseImages().get("FRONT"), "source photo must not be substituted for generated front");
        int[] expected = {Color.RED.getRGB(), Color.GREEN.getRGB(), Color.BLUE.getRGB(), Color.ORANGE.getRGB()}; int i = 0;
        for (String side : List.of("FRONT", "BACK", "LEFT", "RIGHT")) {
            var crop = h.archive.read("org", CommerceImageArchive.sha("org".getBytes()) + "/" + ready.baseImages().get(side));
            var decoded = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(crop.dataBase64())));
            assertEquals(256, decoded.getWidth()); assertEquals(expected[i++], decoded.getRGB(128,128));
        }
        assertEquals(ready, resumed.readyReferenceSet("org", view.id()));
        verify(h.gateway, times(1)).generateArtwork(anyString(), anyString(), any(), anyList());
    }

    @Test void uncertainSubmissionCannotBeResentByPollingRetryOrANewRequestId() throws Exception {
        var h = harness(); String source = h.upload(); var initial = h.service.start(new CommercePrintReferenceService.Request(source, false, "request-one"), user);
        when(h.gateway.generateArtwork(anyString(), anyString(), any(), anyList())).thenThrow(new IllegalStateException("secret upstream body"));
        h.service.process(new CommercePrintReferenceRepository.Work("org", initial.id()), "token");
        assertEquals("UNKNOWN", h.service.get(initial.id(), user).status());
        h.service.process(new CommercePrintReferenceRepository.Work("org", initial.id()), "another-token");
        var replay = h.service.start(new CommercePrintReferenceService.Request(source, false, "new-request-id"), user);
        assertEquals(initial.id(), replay.id()); assertEquals("UNKNOWN", replay.status()); assertFalse(replay.retryable());
        assertThrows(BusinessException.class, () -> h.service.retry(initial.id(), user));
        assertFalse(replay.message().contains("secret")); verify(h.gateway, times(1)).generateArtwork(anyString(), anyString(), any(), anyList());
    }

    @Test void onlyAProvenNotSentFailureCanRequeueTheSameProviderAttempt() throws Exception {
        var h = harness(); var initial = h.service.start(new CommercePrintReferenceService.Request(h.upload(), true, "request-one"), user);
        when(h.gateway.generateArtwork(anyString(), eq(initial.id()), any(), anyList())).thenThrow(new ImageGenerationNotSentException(new RuntimeException())).thenReturn(artifact(sheet()));
        var work = new CommercePrintReferenceRepository.Work("org", initial.id()); h.service.process(work, "token");
        assertTrue(h.service.get(initial.id(), user).retryable());
        assertEquals("QUEUED", h.service.retry(initial.id(), user).status());
        h.service.process(work, "token-two");
        assertEquals("RESULT_RECEIVED", h.service.get(initial.id(), user).status());
        verify(h.gateway, times(2)).generateArtwork(anyString(), eq(initial.id()), any(), anyList());
    }

    @Test void resultDownloadRetryDoesNotCallImageModelAgain() throws Exception {
        var h = harness(); var initial = h.service.start(new CommercePrintReferenceService.Request(h.upload(), false, "request-one"), user);
        when(h.gateway.generateArtwork(anyString(), anyString(), any(), anyList())).thenReturn(new CommerceDesignImageGateway.Response(null,
                new CommerceDesignImageGateway.PendingResult("https://images.invalid/result.png", "image/png", "model", "edit", null)));
        when(h.downloader.download(anyString())).thenThrow(new IllegalStateException("temporary network error"))
                .thenReturn(new MarketplaceImageDownloader.ImageBinary(sheet(), "image/png", "png"));
        var work = new CommercePrintReferenceRepository.Work("org", initial.id());
        h.service.process(work,"submit"); h.service.process(work,"download-one");
        assertEquals("RESULT_RECEIVED", h.service.get(initial.id(),user).status());
        h.service.process(work,"download-two");
        assertEquals("READY", h.service.get(initial.id(), user).status());
        verify(h.gateway, times(1)).generateArtwork(anyString(), anyString(), any(), anyList());
    }

    @Test void malformedSheetNeverBecomesReadyOrTriggersAnotherPaidGeneration() throws Exception {
        var h = harness(); var initial = h.service.start(new CommercePrintReferenceService.Request(h.upload(), false, "request-one"), user);
        when(h.gateway.generateArtwork(anyString(), anyString(), any(), anyList())).thenReturn(artifact(image(128, Color.PINK)));
        var work = new CommercePrintReferenceRepository.Work("org",initial.id());h.service.process(work,"submit");h.service.process(work,"crop");
        var result=h.service.get(initial.id(),user);assertEquals("FAILED",result.status());assertEquals("INVALID_OUTPUT",result.stage());assertFalse(result.retryable());assertTrue(result.baseImages().isEmpty());
        assertThrows(BusinessException.class,()->h.service.readyReferenceSet("org",initial.id()));
        verify(h.gateway,times(1)).generateArtwork(anyString(),anyString(),any(),anyList());
    }

    @Test void requestCollisionAndCrossTenantAccessAreRejectedBeforeGenerating() throws Exception {
        var h=harness();String original=h.upload();var job=h.service.start(new CommercePrintReferenceService.Request(original,false,"request-one"),user);
        assertThrows(BusinessException.class,()->h.service.start(new CommercePrintReferenceService.Request("b".repeat(64),false,"request-one"),user));
        var other=new PlatformPrincipal("other-user","other-org","company","u","u","",true,List.of(),List.of("product:edit","product:view"));
        assertThrows(BusinessException.class,()->h.service.get(job.id(),other));
        assertThrows(BusinessException.class,()->h.service.start(new CommercePrintReferenceService.Request(original,false,"request-two"),other));
        assertThrows(BusinessException.class,()->h.service.get(job.id(),null));verifyNoInteractions(h.gateway);
    }

    @Test void concurrentWorkerLosingTheClaimCannotSubmit() throws Exception {
        var h=harness();var first=h.service.start(new CommercePrintReferenceService.Request(h.upload(),false,"request-one"),user);
        var row=h.jobs.find("org",first.id());assertTrue(h.jobs.claim(row,"winner",false));assertFalse(h.jobs.claim(row,"loser",false));
        h.service.process(new CommercePrintReferenceRepository.Work("org",first.id()),"loser");verifyNoInteractions(h.gateway);
    }

    @Test void promptUsesOneConstructionAndCorrectCellDirectionsWithoutForcingGenericTShirt() {
        String p=CommercePrintReferencePrompt.build(false);
        assertTrue(p.contains("ONE coherent physical construction"));assertTrue(p.contains("TOP LEFT = FRONT; TOP RIGHT = BACK"));
        assertTrue(p.contains("LEFT camera is on +X: the chest/front points toward the image LEFT"));
        assertTrue(p.contains("RIGHT camera is on -X: the chest/front points toward the image RIGHT"));
        assertTrue(p.contains("same physical height, pixel scale, vertical alignment"));assertTrue(p.contains("invisible body"));
        assertTrue(p.contains("Remove the product's decorative prints"));
        assertFalse(CommercePrintReferencePrompt.build(true).contains("Remove the product's decorative prints"));
    }

    @Test void bothCleanupModesUseARelaxedSharedPoseWithoutChangingSewnConstruction() {
        for (boolean alreadyPlain : List.of(false, true)) {
            String p=CommercePrintReferencePrompt.build(alreadyPlain);
            assertTrue(p.contains("ONE naturally relaxed, gently opened ghost-mannequin pose"));
            assertTrue(p.contains("keep that exact pose in all four views"));
            assertTrue(p.contains("Let the hem hang freely under gravity"));
            assertTrue(p.contains("no newly folded-over, tucked-in, knotted or bunched-up hem"));
            assertTrue(p.contains("Preserve intentionally sewn asymmetric hems, permanent overlaps, pleats and layered construction"));
            assertTrue(p.contains("slightly outward and downward from the torso"));
            assertTrue(p.contains("where the actual construction permits"));
            assertTrue(p.contains("Keep natural occlusion in exact side views"));
            assertTrue(p.contains("do not seal existing openings or invent extra holes"));
            assertTrue(p.contains("Retain small natural gravity folds and fabric texture"));
            assertTrue(p.contains("do not force the garment to be symmetric"));
            assertTrue(p.contains("strict orthographic front, back and exact 90-degree side views"));
        }
    }

    @Test void hemEvidenceTakesPriorityOverTemporaryFoldsBeforeTheSharedPoseIsRendered() {
        for (boolean alreadyPlain : List.of(false, true)) {
            String p=CommercePrintReferencePrompt.build(alreadyPlain);
            assertTrue(p.indexOf("STRUCTURE EVIDENCE:") < p.indexOf("CLOTHING POSE:"));
            assertTrue(p.contains("exposed reverse side of the same fabric"));
            assertTrue(p.contains("does NOT prove a second fabric layer, diagonal cut, wrap panel or permanent asymmetric hem"));
            assertTrue(p.contains("clearly visible seams, distinct fabric pieces or actual structural connections"));
            assertTrue(p.contains("For an ordinary T-shirt"));
            assertTrue(p.contains("a straight body and one continuous single-layer level hem"));
            assertTrue(p.contains("do not invent a diagonal cut, second layer, crossed front panels, false side slit or detached flap"));
            assertTrue(p.contains("A clearly evidenced separate inner layer, including one with a different fabric color, must remain"));
            assertTrue(p.contains("Keep clearly evidenced genuine asymmetric, off-shoulder and layered garments as their own construction"));
            assertTrue(p.contains("freeze that construction and pose before rendering all four views"));
        }
    }

    @Test void promptUpgradeKeepsExistingRequestBoundAndOnlyNewRequestsUseTheNewVersion() throws Exception {
        for (String legacyVersion : List.of("garment-four-view-v1", "garment-four-view-v2")) {
            var h=harness(); String source=h.upload(); String legacyId=UUID.randomUUID().toString();
            var legacy=new CommercePrintReferenceRepository.Job(legacyId,"org","user","legacy-request",source,false,
                    legacyVersion,"Saved legacy prompt","model-config","image-model","UNKNOWN","UNKNOWN",
                    "Existing result awaits review",null,null,null,null,0);
            h.jobs.insert(legacy);
            var old=h.service.start(new CommercePrintReferenceService.Request(source,false,"legacy-request"),user);
            assertEquals(legacyId,old.id()); assertEquals("UNKNOWN",old.status()); assertEquals(legacyVersion,old.promptVersion());
            var next=h.service.start(new CommercePrintReferenceService.Request(source,false,"new-pose-request"),user);
            assertNotEquals(legacyId,next.id()); assertEquals("QUEUED",next.status()); assertEquals("garment-four-view-v3",next.promptVersion());
            assertEquals(legacy,h.jobs.find("org",legacyId),"the prompt upgrade must not reset or rewrite an existing task");
            assertEquals(2,h.jobs.rows.size()); verifyNoInteractions(h.gateway);
        }
    }

    private Harness harness() {
        var jobs=new MemoryJobs();var models=mock(ModelConfigService.class);when(models.imageConnection(anyString())).thenReturn(Optional.of(model));when(models.connectionById(anyString(),eq(model.id()))).thenReturn(Optional.of(model));
        var gateway=mock(CommerceDesignImageGateway.class);var archive=new CommerceImageArchive(temp.toString());var downloader=mock(MarketplaceImageDownloader.class);
        return new Harness(jobs,models,gateway,archive,downloader,new CommercePrintReferenceService(jobs,models,gateway,archive,downloader,json));
    }
    private record Harness(MemoryJobs jobs,ModelConfigService models,CommerceDesignImageGateway gateway,CommerceImageArchive archive,MarketplaceImageDownloader downloader,CommercePrintReferenceService service) {
        String upload()throws Exception{return archive.store("org",image(128,Color.ORANGE)).sha256();}
    }
    private static byte[] image(int size,Color color)throws IOException {
        var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,size,size);g.setColor(color);g.fillRect(size/4,size/4,size/2,size/2);g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    private static byte[] sheet()throws IOException {
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,512,512);Color[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.ORANGE};
        for(int i=0;i<4;i++){g.setColor(colors[i]);g.fillRect((i%2)*256+64,(i/2)*256+64,128,128);}g.dispose();var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    private static CommerceDesignImageGateway.Response artifact(byte[] bytes){return new CommerceDesignImageGateway.Response(new CommerceDesignImageGateway.Artifact(Base64.getEncoder().encodeToString(bytes),"image/png","image-model","edit",null),null);}

    /** In-memory durable ledger shared across service instances; mirrors CAS effects, not image work. */
    private static final class MemoryJobs extends CommercePrintReferenceRepository {
        final Map<String,Job> rows=new LinkedHashMap<>();final Map<String,RequestKey> keys=new HashMap<>();MemoryJobs(){super(null);}
        @Override public synchronized RequestKey requestKey(String org,String request){return keys.get(org+":"+request);}
        @Override public synchronized RequestKey bindRequest(String org,String request,String source,boolean plain,String job){keys.putIfAbsent(org+":"+request,new RequestKey(job,source,plain));return requestKey(org,request);}
        @Override public synchronized Job find(String org,String id){var j=rows.get(id);return j!=null&&j.organizationId().equals(org)?j:null;}
        @Override public synchronized Job byRequest(String org,String request){return rows.values().stream().filter(j->j.organizationId().equals(org)&&j.requestId().equals(request)).findFirst().orElse(null);}
        @Override public synchronized Job bySource(String org,String source,String version){return rows.values().stream().filter(j->j.organizationId().equals(org)&&j.originalImageId().equals(source)&&j.promptVersion().equals(version)).findFirst().orElse(null);}
        @Override public synchronized void insert(Job j){if(byRequest(j.organizationId(),j.requestId())==null&&bySource(j.organizationId(),j.originalImageId(),j.promptVersion())==null)rows.put(j.id(),j);}
        private void update(Job j,String state,String stage,String token,String result,String sheet,String images){var old=rows.get(j.id());rows.put(j.id(),new Job(old.id(),old.organizationId(),old.createdBy(),old.requestId(),old.originalImageId(),old.alreadyPlain(),old.promptVersion(),old.prompt(),old.modelConfigId(),old.modelId(),state,stage,"job state updated",result,sheet,images,token,old.collectAttempts()));}
        @Override public synchronized boolean claim(Job j,String token,boolean collect){var old=rows.get(j.id());if(!(collect?"RESULT_RECEIVED":"QUEUED").equals(old.status()))return false;update(old,collect?"COLLECTING":"SUBMITTING",collect?"SPLITTING":"GENERATING",token,old.resultJson(),old.sheetImageId(),old.baseImagesJson());return true;}
        @Override public synchronized void received(Job j,String token,String result){var old=rows.get(j.id());assertEquals(token,old.leaseToken());update(old,"RESULT_RECEIVED","RESULT_RECEIVED",null,result,null,null);}
        @Override public synchronized void ready(Job j,String token,String sheet,String images){var old=rows.get(j.id());assertEquals(token,old.leaseToken());update(old,"READY","READY",null,old.resultJson(),sheet,images);}
        @Override public synchronized void uncertain(Job j,String token){var old=rows.get(j.id());update(old,"UNKNOWN","OUTCOME_UNKNOWN",token,old.resultJson(),null,null);}
        @Override public synchronized void failed(Job j,String token,String stage,String message){var old=rows.get(j.id());update(old,"FAILED",stage,null,old.resultJson(),null,null);}
        @Override public synchronized void preflightFailed(Job j,String stage,String message){failed(j,null,stage,message);}
        @Override public synchronized void collectRetry(Job j,String token){var old=rows.get(j.id());update(old,"RESULT_RECEIVED","DOWNLOADING",null,old.resultJson(),null,null);}
        @Override public synchronized void retrySafe(Job j){update(j,"COLLECT_FAILED".equals(j.stage())?"RESULT_RECEIVED":"QUEUED","QUEUED",null,j.resultJson(),null,null);}
    }
}
