package org.openphc.cce.protocol.service;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.TriggerIndex;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.openphc.cce.protocol.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.protocol.domain.repository.TriggerIndexRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Loading a PlanDefinition with visit-driven step groups through the real parser: the
 * {@code cycle-boundary} extension is accepted, and the rules that come with it are enforced at load.
 */
@ExtendWith(MockitoExtension.class)
class VisitDrivenGroupLoadTest {

    @Mock
    private ProtocolDefinitionRepository protocolDefinitionRepository;

    @Mock
    private TriggerIndexRepository triggerIndexRepository;

    private ProtocolDefinitionService service;
    private String journeyJson;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new ProtocolDefinitionService(
                protocolDefinitionRepository,
                triggerIndexRepository,
                new PlanDefinitionParser(FhirContext.forR4()),
                objectMapper);

        try (InputStream is = getClass().getResourceAsStream("/fhir/plan-definition-patient-journey.json")) {
            assertNotNull(is, "Test fixture not found");
            journeyJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void loadProtocol_calendarDayJourneyHoldingAnEntryStepConsultation_isLoaded() {
        UUID id = UUID.randomUUID();
        when(protocolDefinitionRepository.findByUrlAndVersion(any(), any())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any())).thenAnswer(i -> {
            ProtocolDefinition d = i.getArgument(0);
            d.setId(id);
            return d;
        });

        ProtocolDefinition saved = service.loadProtocol(journeyJson);

        assertEquals(ProtocolDefinitionStatus.ACTIVE, saved.getStatus());
        // Only steps are indexed: a group is never matched to an event.
        ArgumentCaptor<List<TriggerIndex>> captor = ArgumentCaptor.forClass(List.class);
        verify(triggerIndexRepository).saveAll(captor.capture());
        assertEquals(List.of("check-in", "examination", "history"),
                captor.getValue().stream().map(row -> row.getId().getActionId()).sorted().toList());
    }

    @Test
    void loadProtocol_rwandaHealthCenterServiceProtocol_isLoaded() throws IOException {
        // The service protocol for Rwanda's health centers: an outpatient visit per calendar day.
        String rwandaJson;
        try (InputStream is = getClass().getResourceAsStream("/fhir/rwanda-outpatient-journey-step-groups.json")) {
            assertNotNull(is, "Test fixture not found");
            rwandaJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        when(protocolDefinitionRepository.findByUrlAndVersion(any(), any())).thenReturn(Optional.empty());
        when(protocolDefinitionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProtocolDefinition saved = service.loadProtocol(rwandaJson);

        assertEquals(ProtocolDefinitionStatus.ACTIVE, saved.getStatus());
        // Every step is indexed, sub-steps included. The visit group is not.
        ArgumentCaptor<List<TriggerIndex>> captor = ArgumentCaptor.forClass(List.class);
        verify(triggerIndexRepository).saveAll(captor.capture());
        assertEquals(List.of("chief-complaints", "consultation", "diagnosis", "history-assessment", "lab-order",
                        "lab-results", "referral", "treatment", "visit-encounter", "vitals-recording"),
                captor.getValue().stream().map(row -> row.getId().getActionId()).distinct().sorted().toList());
    }

    @Test
    void loadProtocol_calendarDayGroupInsideARepeatingGroup_isRejected() {
        // Every event of one visit falls on the same day, so a Consultation repeating by calendar-day
        // inside a calendar-day journey could never start a second cycle.
        String json = journeyJson.replace("\"valueCode\": \"entry-step\"", "\"valueCode\": \"calendar-day\"");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("'consultation' repeats by calendar-day inside a group that repeats by calendar-day"),
                ex.getMessage());

        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void loadProtocol_groupDeclaringRequiredBehavior_isRejected_everyGroupIsOptionalForNow() {
        String json = journeyJson.replace("\"groupingBehavior\": \"logical-group\",",
                "\"groupingBehavior\": \"logical-group\", \"requiredBehavior\": \"must\",");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("Group 'journey' declares requiredBehavior 'must'. Mandatory groups "
                + "are not supported yet"), ex.getMessage());

        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void loadProtocol_unknownCycleBoundary_isRejected() {
        String json = journeyJson.replace("\"valueCode\": \"entry-step\"", "\"valueCode\": \"encounter\"");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("Supported codes: calendar-day, entry-step"), ex.getMessage());

        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void loadProtocol_entryStepGroupNamingNoEntryStep_isRejected() {
        String json = journeyJson.replace(
                ",\n            {\n              \"url\": \"http://openphc.org/fhir/StructureDefinition/cycle-entry-step\",\n"
                        + "              \"valueId\": \"history\"\n            }", "");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("'consultation' repeats by entry-step but names no entry step"),
                ex.getMessage());

        verify(protocolDefinitionRepository, never()).save(any());
    }

    @Test
    void loadProtocol_entryStepThatWaitsInsideItsGroup_isRejected() {
        // History waiting on the examination could never start a cycle.
        String json = journeyJson.replace(
                "\"id\": \"history\",\n              \"title\": \"History\",",
                "\"id\": \"history\",\n              \"title\": \"History\",\n"
                        + "              \"relatedAction\": [{\"actionId\": \"examination\", \"relationship\": \"after-end\"}],");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("'consultation' names 'history' as its entry step, but it waits on another action inside the group"),
                ex.getMessage());

        verify(protocolDefinitionRepository, never()).save(any());
    }
}
