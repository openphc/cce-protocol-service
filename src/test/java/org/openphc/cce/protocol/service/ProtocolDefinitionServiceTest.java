package org.openphc.cce.protocol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.TriggerIndex;
import org.mockito.InOrder;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.protocol.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.protocol.domain.repository.TriggerIndexRepository;
import org.openphc.cce.common.fhir.PlanDefinitionParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProtocolDefinitionServiceTest {

    @Mock
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Mock
    private TriggerIndexRepository triggerIndexRepository;

    @Mock
    private PlanDefinitionParser planDefinitionParser;


    private ProtocolDefinitionService service;
    private ObjectMapper objectMapper;
    private String planDefinitionJson;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ProtocolDefinitionService(
                protocolDefinitionRepository,
                triggerIndexRepository,
                planDefinitionParser,
                objectMapper);

        try (InputStream is = getClass().getResourceAsStream("/fhir/plan-definition-anc-high-risk.json")) {
            assertNotNull(is, "Test fixture not found");
            planDefinitionJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── Load Protocol Tests ──

    @Test
    void loadProtocol_duplicateUrlVersion_throwsIllegalArgument() {
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion("http://openphc.org/test", "1.0.0"))
                .thenReturn(Optional.of(ProtocolDefinition.builder().build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(planDefinitionJson));
        assertTrue(ex.getMessage().contains("already exists"));

        verify(protocolDefinitionRepository, never()).save(any());
        verify(triggerIndexRepository, never()).saveAll(anyList());
    }

    @Test
    void loadProtocol_invalidTrigger_throwsIllegalArgument() {
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        doThrow(new IllegalArgumentException("trigger with no data[] and no condition"))
                .when(planDefinitionParser).validateTriggers(planDef);

        assertThrows(IllegalArgumentException.class, () -> service.loadProtocol(planDefinitionJson));

        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void loadProtocol_invalidStepGroup_throwsIllegalArgument() {
        // A group with a trigger, say: it would never be matched, and its steps would be refused
        // nothing. Refused at load, before anything is persisted.
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        doThrow(new IllegalArgumentException("Group 'visit-2' has a trigger."))
                .when(planDefinitionParser).validateGroups(planDef);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(planDefinitionJson));
        assertTrue(ex.getMessage().contains("visit-2"));

        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void loadProtocol_optionalActionWithADeadline_throwsIllegalArgument() {
        // Only a mandatory step can be late, so a deadline on an optional action enforces nothing.
        // Refused at load: nothing downstream would ever tell the author their SLA is inert.
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        doThrow(new IllegalArgumentException("Optional actions must not declare a deadline: "
                + "action 'referral' (requiredBehavior=could, tolerance-days=3)"))
                .when(planDefinitionParser).validateOptionalStepDeadlines(planDef);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(planDefinitionJson));
        assertTrue(ex.getMessage().contains("referral"));

        verify(protocolDefinitionRepository, never()).save(any());
        verify(triggerIndexRepository, never()).saveAll(anyList());
    }

    // ── Retire Protocol Tests ──

    @Test
    void retireProtocol_alreadyRetired_throwsIllegalState() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.RETIRED);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));

        assertThrows(IllegalStateException.class, () -> service.retireProtocol(id));

        verify(protocolDefinitionRepository, never()).save(any());
        verify(triggerIndexRepository, never()).deleteByProtocolDefinitionId(any());
    }

    @Test
    void retireProtocol_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.retireProtocol(id));
    }

    // ── Rebuild Index Tests ──

    @Test
    void rebuildIndex_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.rebuildIndex(id));
    }

    // ── Delete Protocol Tests ──
    @Test
    void deleteProtocol_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.deleteProtocol(id));
    }

    // ── Read Operation Tests ──

    @Test
    void findById_existing_returnsDefinition() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition protocolDef = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(protocolDef));

        ProtocolDefinition result = service.findById(id);

        assertEquals(id, result.getId());
    }

    @Test
    void findById_notFound_throwsEntityNotFound() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.findById(id));
    }

    @Test
    void findAll_delegatesToRepository() {
        List<ProtocolDefinition> defs = List.of(
                buildProtocolDefinition(UUID.randomUUID(), ProtocolDefinitionStatus.ACTIVE));
        when(protocolDefinitionRepository.findAll()).thenReturn(defs);

        List<ProtocolDefinition> result = service.findAll();

        assertEquals(1, result.size());
    }

    @Test
    void findByUrl_delegatesToRepository() {
        String url = "http://openphc.org/test";
        when(protocolDefinitionRepository.findByUrl(url)).thenReturn(List.of());

        List<ProtocolDefinition> result = service.findByUrl(url);

        assertTrue(result.isEmpty());
        verify(protocolDefinitionRepository).findByUrl(url);
    }

    @Test
    void findByUrlAndVersion_delegatesToRepository() {
        String url = "http://openphc.org/test";
        String version = "1.0.0";
        when(protocolDefinitionRepository.findByUrlAndVersion(url, version)).thenReturn(Optional.empty());

        Optional<ProtocolDefinition> result = service.findByUrlAndVersion(url, version);

        assertTrue(result.isEmpty());
        verify(protocolDefinitionRepository).findByUrlAndVersion(url, version);
    }

    // ── Helpers ──

    private PlanDefinition mockPlanDefinition(String url, String version, int actionCount) {
        PlanDefinition planDef = mock(PlanDefinition.class);
        when(planDef.getUrl()).thenReturn(url);
        when(planDef.getVersion()).thenReturn(version);

        List<PlanDefinition.PlanDefinitionActionComponent> actions = new java.util.ArrayList<>();
        for (int i = 0; i < actionCount; i++) {
            actions.add(new PlanDefinition.PlanDefinitionActionComponent());
        }
        when(planDef.getAction()).thenReturn(actions);

        return planDef;
    }

    // ── Happy paths ──

    @Test
    void loadProtocol_persistsTheDefinitionAndItsTriggerIndex() {
        UUID id = UUID.randomUUID();
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion(any(), any())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any())).thenAnswer(i -> {
            ProtocolDefinition d = i.getArgument(0);
            d.setId(id);
            return d;
        });
        when(planDefinitionParser.buildTriggerIndexEntries(eq(planDef), eq(id))).thenReturn(List.of(
                new PlanDefinitionParser.TriggerIndexEntry(
                        org.hl7.fhir.r4.model.ResourceType.Encounter, "type",
                        "http://openphc.org/encounter-types", "anc-visit", id, "initial-enrollment")));
        when(planDefinitionParser.extractSteps(planDef)).thenReturn(List.of());

        ProtocolDefinition saved = service.loadProtocol(planDefinitionJson);

        assertEquals(ProtocolDefinitionStatus.ACTIVE, saved.getStatus());
        assertEquals("http://openphc.org/test", saved.getUrl());
        assertNotNull(saved.getLoadedAt());
        // the parser's persistence-agnostic coordinates are mapped onto this service's entity
        ArgumentCaptor<List<TriggerIndex>> captor = ArgumentCaptor.forClass(List.class);
        verify(triggerIndexRepository).saveAll(captor.capture());
        TriggerIndex row = captor.getValue().get(0);
        assertEquals(org.hl7.fhir.r4.model.ResourceType.Encounter, row.getId().getResourceType());
        assertEquals("type", row.getId().getPath());
        assertEquals("anc-visit", row.getId().getCodeValue());
        assertEquals(id, row.getId().getProtocolDefinitionId());
        assertEquals("initial-enrollment", row.getId().getActionId());
    }

    @Test
    void loadProtocol_validatesBeforePersisting() {
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion(any(), any())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any())).thenAnswer(i -> {
            ProtocolDefinition d = i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        when(planDefinitionParser.extractSteps(planDef)).thenReturn(List.of());

        service.loadProtocol(planDefinitionJson);

        verify(planDefinitionParser).validateActionIds(planDef);
        verify(planDefinitionParser).validateActionTypes(planDef);
        verify(planDefinitionParser).validateTriggers(planDef);
        verify(planDefinitionParser).validateGroups(planDef);
        verify(planDefinitionParser).validateOptionalStepDeadlines(planDef);
    }

    @Test
    void loadProtocol_warnsButStillLoadsWhenAnEdgeIsInert() {
        // A concurrent-* or dangling relatedAction establishes no ordering. That is reported, not fatal:
        // both shapes were accepted before, so rejecting would break an upstream publisher.
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(planDefinitionJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion(any(), any())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any())).thenAnswer(i -> {
            ProtocolDefinition d = i.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });
        PlanDefinitionParser.StepMetadata step = new PlanDefinitionParser.StepMetadata(
                "a", "A", List.of(),
                List.of(new PlanDefinitionParser.RelatedStepInfo("ghost", "concurrent", null, null)),
                null, null, null, List.of());
        when(planDefinitionParser.extractSteps(planDef)).thenReturn(List.of(step));

        assertDoesNotThrow(() -> service.loadProtocol(planDefinitionJson));
        verify(protocolDefinitionRepository).save(any());
    }

    @Test
    void retireProtocol_marksRetiredAndStopsItMatching() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition def = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(def));
        when(protocolDefinitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProtocolDefinition retired = service.retireProtocol(id);

        assertEquals(ProtocolDefinitionStatus.RETIRED, retired.getStatus());
        // its trigger index rows go, so inbound events stop matching it
        verify(triggerIndexRepository).deleteByProtocolDefinitionId(id);
    }

    @Test
    void rebuildIndex_replacesTheExistingRows() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition def = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(def));
        when(planDefinitionParser.parse(anyString())).thenReturn(planDef);
        when(planDefinitionParser.buildTriggerIndexEntries(eq(planDef), eq(id))).thenReturn(List.of());

        service.rebuildIndex(id);

        InOrder order = inOrder(triggerIndexRepository);
        order.verify(triggerIndexRepository).deleteByProtocolDefinitionId(id);
        order.verify(triggerIndexRepository).saveAll(anyList());
    }

    @Test
    void deleteProtocol_removesTheIndexThenTheDefinition() {
        UUID id = UUID.randomUUID();
        ProtocolDefinition def = buildProtocolDefinition(id, ProtocolDefinitionStatus.RETIRED);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(def));

        service.deleteProtocol(id);

        verify(triggerIndexRepository).deleteByProtocolDefinitionId(id);
        verify(protocolDefinitionRepository).delete(def);
    }

    @Test
    void deleteProtocol_enrolledDefinition_reportsTheConstraintClearly() {
        // protocol_instance belongs to the Matcher Service, so its foreign key is what blocks the
        // delete rather than a pre-check reading another service's table.
        UUID id = UUID.randomUUID();
        ProtocolDefinition def = buildProtocolDefinition(id, ProtocolDefinitionStatus.ACTIVE);
        when(protocolDefinitionRepository.findById(id)).thenReturn(Optional.of(def));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("fk"))
                .when(protocolDefinitionRepository).flush();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.deleteProtocol(id));
        assertTrue(ex.getMessage().contains("existing protocol instances"));
    }

    @Test
    void loadProtocol_unstorableJson_throwsIllegalArgument() {
        // The FHIR parser and the JSON column reader are separate readers over the same payload.
        // If the second one cannot handle it there is nothing to store, so this fails the load
        // rather than persisting a definition with an empty body.
        String notJson = "this is not json";
        PlanDefinition planDef = mockPlanDefinitionMinimal("http://openphc.org/test", "1.0.0");
        when(planDefinitionParser.parse(notJson)).thenReturn(planDef);
        when(protocolDefinitionRepository.findByUrlAndVersion(any(), any())).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(notJson));
        assertTrue(ex.getMessage().contains("Failed to parse PlanDefinition JSON for storage"));
        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void findAll_pagedDelegatesToRepository() {
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, 20);
        when(protocolDefinitionRepository.findAll(pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(buildProtocolDefinition(UUID.randomUUID(), ProtocolDefinitionStatus.ACTIVE)),
                        pageable, 1));

        assertEquals(1, service.findAll(pageable).getTotalElements());
    }

    private PlanDefinition mockPlanDefinitionMinimal(String url, String version) {
        PlanDefinition planDef = mock(PlanDefinition.class);
        lenient().when(planDef.getUrl()).thenReturn(url);
        lenient().when(planDef.getVersion()).thenReturn(version);
        return planDef;
    }

    private ProtocolDefinition buildProtocolDefinition(UUID id, ProtocolDefinitionStatus status) {
        return ProtocolDefinition.builder()
                .id(id)
                .url("http://openphc.org/PlanDefinition/anc-high-risk")
                .version("1.0.0")
                .status(status)
                .definition(objectMapper.createObjectNode().put("resourceType", "PlanDefinition"))
                .loadedAt(OffsetDateTime.now())
                .build();
    }

}
