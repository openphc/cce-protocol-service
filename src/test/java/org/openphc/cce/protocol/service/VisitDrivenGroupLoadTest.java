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
    void loadProtocol_calendarDayGroupInsideARepeatingGroup_isRejected() {
        // Every event of one visit falls on the same day, so a Consultation repeating by calendar-day
        // inside a calendar-day journey could never start a second cycle.
        String json = journeyJson.replace("\"valueCode\": \"entry-step\"", "\"valueCode\": \"calendar-day\"");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("'consultation' repeats by calendar-day inside another repeating group"),
                ex.getMessage());

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
    void loadProtocol_entryStepGroupWithNoEntryStep_isRejected() {
        // History waiting on the examination as well makes a cycle with no step to start it.
        String json = journeyJson.replace(
                "\"id\": \"history\",\n              \"title\": \"History\",",
                "\"id\": \"history\",\n              \"title\": \"History\",\n"
                        + "              \"relatedAction\": [{\"actionId\": \"examination\", \"relationship\": \"after-end\"}],");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.loadProtocol(json));
        assertTrue(ex.getMessage().contains("'consultation' repeats by entry-step but has no entry step"),
                ex.getMessage());

        verify(protocolDefinitionRepository, never()).save(any());
    }
}
