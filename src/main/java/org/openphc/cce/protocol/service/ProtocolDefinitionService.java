package org.openphc.cce.protocol.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.openphc.cce.common.entity.ProtocolDefinition;
import org.openphc.cce.common.entity.TriggerIndex;
import org.openphc.cce.common.entity.TriggerIndexId;
import org.springframework.dao.DataIntegrityViolationException;
import org.openphc.cce.common.enums.ProtocolDefinitionStatus;
import org.openphc.cce.protocol.domain.repository.ProtocolDefinitionRepository;
import org.openphc.cce.protocol.domain.repository.TriggerIndexRepository;
import org.openphc.cce.common.fhir.PlanDefinitionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ProtocolDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDefinitionService.class);

    private final ProtocolDefinitionRepository protocolDefinitionRepository;
    private final TriggerIndexRepository triggerIndexRepository;
    private final PlanDefinitionParser planDefinitionParser;
    private final ObjectMapper objectMapper;

    public ProtocolDefinitionService(ProtocolDefinitionRepository protocolDefinitionRepository,
                                     TriggerIndexRepository triggerIndexRepository,
                                     PlanDefinitionParser planDefinitionParser,
                                     ObjectMapper objectMapper) {
        this.protocolDefinitionRepository = protocolDefinitionRepository;
        this.triggerIndexRepository = triggerIndexRepository;
        this.planDefinitionParser = planDefinitionParser;
        this.objectMapper = objectMapper;
    }

    /**
     * Load a PlanDefinition JSON, parse it, persist the protocol definition,
     * build trigger index entries, and register condition-only triggers.
     *
     * @param planDefinitionJson the raw FHIR PlanDefinition JSON
     * @return the persisted ProtocolDefinition entity
     * @throws IllegalArgumentException if the JSON is invalid or a duplicate (url, version) exists
     */
    public ProtocolDefinition loadProtocol(String planDefinitionJson) {
        // Parse and validate
        PlanDefinition planDefinition = planDefinitionParser.parse(planDefinitionJson);
        planDefinitionParser.validateActionIds(planDefinition);
        planDefinitionParser.validateActionTypes(planDefinition);
        planDefinitionParser.validateTriggers(planDefinition);
        planDefinitionParser.validateGroups(planDefinition);
        // Only a mandatory step can be late, so only a mandatory step may carry a deadline. Rejected
        // here rather than ignored downstream: an author who writes tolerance-days on an optional
        // action believes a deadline is being enforced, and nothing later would tell them otherwise.
        planDefinitionParser.validateOptionalStepDeadlines(planDefinition);
        warnOnInertRelatedActions(planDefinition);

        String url = planDefinition.getUrl();
        String version = planDefinition.getVersion();

        // Check for duplicate (url, version)
        if (protocolDefinitionRepository.findByUrlAndVersion(url, version).isPresent()) {
            throw new IllegalArgumentException(
                    "Protocol definition already exists for url=" + url + ", version=" + version);
        }

        // Persist the protocol definition
        JsonNode definitionNode;
        try {
            definitionNode = objectMapper.readTree(planDefinitionJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse PlanDefinition JSON for storage", e);
        }

        ProtocolDefinition protocolDef = ProtocolDefinition.builder()
                .url(url)
                .version(version)
                .status(ProtocolDefinitionStatus.ACTIVE)
                .definition(definitionNode)
                .loadedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        protocolDef = protocolDefinitionRepository.save(protocolDef);
        UUID protocolDefId = protocolDef.getId();

        // Build and persist trigger index entries
        List<TriggerIndex> indexEntries = toTriggerIndexRows(
                planDefinitionParser.buildTriggerIndexEntries(planDefinition, protocolDefId));
        triggerIndexRepository.saveAll(indexEntries);

        int actionCount = planDefinition.getAction().size();

        log.info("Loaded protocol definition: {} (id={}, actions={}, indexEntries={})",
                protocolDef.getCanonical(), protocolDefId, actionCount, indexEntries.size());

        return protocolDef;
    }

    /**
     * Retire a protocol definition. Sets status to RETIRED and deletes its trigger index entries, so
     * it stops matching inbound events. Existing enrollments are not affected.
     */
    public ProtocolDefinition retireProtocol(UUID id) {
        ProtocolDefinition protocolDef = findByIdOrThrow(id);

        if (protocolDef.getStatus() == ProtocolDefinitionStatus.RETIRED) {
            throw new IllegalStateException("Protocol definition is already retired: " + id);
        }

        protocolDef.setStatus(ProtocolDefinitionStatus.RETIRED);
        protocolDef = protocolDefinitionRepository.save(protocolDef);

        // Delete trigger index entries
        triggerIndexRepository.deleteByProtocolDefinitionId(id);



        log.info("Retired protocol definition: {} (id={})", protocolDef.getCanonical(), id);

        return protocolDef;
    }

    /**
     * Rebuild the trigger index for a protocol definition from its stored definition JSONB.
     */
    public void rebuildIndex(UUID id) {
        ProtocolDefinition protocolDef = findByIdOrThrow(id);

        // Delete existing entries
        triggerIndexRepository.deleteByProtocolDefinitionId(id);

        // Re-parse the stored definition
        PlanDefinition planDefinition = planDefinitionParser.parse(protocolDef.getDefinition().toString());

        // Rebuild trigger index
        List<TriggerIndex> indexEntries = toTriggerIndexRows(
                planDefinitionParser.buildTriggerIndexEntries(planDefinition, id));
        triggerIndexRepository.saveAll(indexEntries);


        log.info("Rebuilt trigger index for protocol definition: {} (id={}, indexEntries={})",
                protocolDef.getCanonical(), id, indexEntries.size());
    }

    /**
     * Delete a protocol definition.
     *
     * <p>A definition that any service has already enrolled a patient against cannot be deleted.
     * That is enforced by {@code protocol_instance}'s foreign key rather than by a pre-check here:
     * the table belongs to the Matcher Service, and reading it would couple this service to another
     * service's schema for no gain. The constraint violation is translated into a clear failure.
     */
    public void deleteProtocol(UUID id) {
        ProtocolDefinition protocolDef = findByIdOrThrow(id);

        triggerIndexRepository.deleteByProtocolDefinitionId(id);

        try {
            protocolDefinitionRepository.delete(protocolDef);
            protocolDefinitionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(
                    "Cannot delete protocol definition with existing protocol instances: " + id, e);
        }

        log.info("Deleted protocol definition: {} (id={})", protocolDef.getCanonical(), id);
    }

    /**
     * Map the parser's persistence-agnostic trigger coordinates onto rows of the {@code trigger_index}
     * table this service owns.
     */
    private List<TriggerIndex> toTriggerIndexRows(
            List<PlanDefinitionParser.TriggerIndexEntry> entries) {
        return entries.stream()
                .map(e -> TriggerIndex.builder()
                        .id(TriggerIndexId.builder()
                                .resourceType(e.resourceType())
                                .path(e.path())
                                .codeSystem(e.codeSystem())
                                .codeValue(e.codeValue())
                                .protocolDefinitionId(e.protocolDefinitionId())
                                .actionId(e.actionId())
                                .build())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public ProtocolDefinition findById(UUID id) {
        return findByIdOrThrow(id);
    }

    @Transactional(readOnly = true)
    public List<ProtocolDefinition> findAll() {
        return protocolDefinitionRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Page<ProtocolDefinition> findAll(Pageable pageable) {
        return protocolDefinitionRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public List<ProtocolDefinition> findByUrl(String url) {
        return protocolDefinitionRepository.findByUrl(url);
    }

    @Transactional(readOnly = true)
    public Optional<ProtocolDefinition> findByUrlAndVersion(String url, String version) {
        return protocolDefinitionRepository.findByUrlAndVersion(url, version);
    }

    private ProtocolDefinition findByIdOrThrow(UUID id) {
        return protocolDefinitionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Protocol definition not found: " + id));
    }

    /**
     * Warn about {@code relatedAction} entries that establish no step ordering, so a protocol that
     * expects one to sequence its steps is flagged at load time rather than quietly producing a
     * disconnected graph — no progressive instantiation, no ORDER_VIOLATION, no backfill along that
     * edge, and no error to explain why.
     *
     * <p>Warnings rather than rejections: both shapes were accepted (and equally inert) before, so
     * failing the load would break an upstream publisher for something already in the wild.
     */
    private void warnOnInertRelatedActions(PlanDefinition planDefinition) {
        List<PlanDefinitionParser.StepMetadata> steps = planDefinitionParser.extractSteps(planDefinition);
        List<PlanDefinitionParser.GroupMetadata> groups = planDefinitionParser.extractGroups(planDefinition);

        for (String dangling : PlanDefinitionParser.findDanglingRelatedActions(steps, groups)) {
            log.warn("Protocol {} has a relatedAction naming an action that does not exist — it is "
                            + "ignored and establishes no ordering: {}",
                    planDefinition.getUrl(), dangling);
        }

        for (String unordered : PlanDefinitionParser.findUnorderedRelationships(steps, groups)) {
            log.warn("Protocol {} has a concurrent-* relatedAction, which states no ordering — it "
                            + "will NOT sequence these steps. Use after-*/before-* if one must "
                            + "follow the other: {}",
                    planDefinition.getUrl(), unordered);
        }
    }
}
