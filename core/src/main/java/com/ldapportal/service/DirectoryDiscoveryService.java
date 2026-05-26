// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.discovery.CommitDiscoveryRequest;
import com.ldapportal.dto.discovery.CommitDiscoveryResponse;
import com.ldapportal.dto.discovery.DiscoveryProposalResponse;
import com.ldapportal.dto.discovery.DiscoveryProposalResponse.*;
import com.ldapportal.dto.discovery.DiscoveryRequest;
import com.ldapportal.dto.profile.CreateProfileRequest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryGroupBaseDn;
import com.ldapportal.entity.DirectoryUserBaseDn;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapBrowseService.SearchEntry;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdapSchemaService.AttributeTypeInfo;
import com.ldapportal.ldap.LdapSchemaService.ObjectClassAttributes;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DirectoryGroupBaseDnRepository;
import com.ldapportal.repository.DirectoryUserBaseDnRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.unboundid.ldap.sdk.SearchScope;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Read-only scanning of an existing LDAP directory to propose provisioning
 * profiles, attribute configs, base DNs, and group assignments.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DirectoryDiscoveryService {

    private final DirectoryConnectionRepository dirRepo;
    private final ProvisioningProfileRepository profileRepo;
    private final DirectoryUserBaseDnRepository userBaseDnRepo;
    private final DirectoryGroupBaseDnRepository groupBaseDnRepo;
    private final LdapBrowseService browseService;
    private final LdapSchemaService schemaService;
    private final LdapGroupService groupService;
    private final ProvisioningProfileService profileService;

    /** Person-class filter portable across OpenLDAP and AD. */
    private static final String PERSON_FILTER =
            "(|(objectClass=inetOrgPerson)(&(objectClass=user)(!(objectClass=computer))))";

    /** Group-class filter portable across OpenLDAP and AD. */
    private static final String GROUP_FILTER =
            "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)" +
            "(objectClass=posixGroup)(objectClass=group)(objectClass=groupOfURLs))";

    /** OU-class filter including containers (for AD). */
    private static final String OU_FILTER =
            "(|(objectClass=organizationalUnit)(objectClass=container)(objectClass=organization))";

    // ── Syntax OID → InputType mapping ───────────────────────────────────

    private static final Map<String, String> SYNTAX_INPUT_TYPE_MAP = Map.ofEntries(
            Map.entry("1.3.6.1.4.1.1466.115.121.1.7",  "BOOLEAN"),        // Boolean
            Map.entry("1.3.6.1.4.1.1466.115.121.1.24", "DATETIME"),       // Generalized Time
            Map.entry("1.3.6.1.4.1.1466.115.121.1.12", "DN_LOOKUP")       // DN
    );

    /** Binary/image syntaxes to skip entirely. */
    private static final Set<String> SKIP_SYNTAXES = Set.of(
            "1.3.6.1.4.1.1466.115.121.1.5",   // Binary
            "1.3.6.1.4.1.1466.115.121.1.28"    // JPEG
    );

    /** Well-known attribute name overrides. */
    private static final Map<String, String> ATTR_NAME_OVERRIDES = Map.of(
            "userpassword",    "PASSWORD",
            "description",     "TEXTAREA",
            "postaladdress",   "TEXTAREA",
            "jpegphoto",       "SKIP"
    );

    /** Attributes that should never appear in a profile config. */
    private static final Set<String> SYSTEM_ATTRIBUTES = Set.of(
            "objectclass", "entryuuid", "entrydn", "subschemasubentry",
            "structuralobjectclass", "hassubordinates", "numsubordinates",
            "creatorsname", "createtimestamp", "modifiersname", "modifytimestamp",
            "entrycsn", "contextcsn", "modifyTimestamp", "whenCreated", "whenChanged",
            "uSNCreated", "uSNChanged", "distinguishedName", "instanceType",
            "objectCategory", "objectGUID", "objectSid", "pwdLastSet",
            "badPwdCount", "badPasswordTime", "lastLogon", "lastLogoff",
            "logonCount", "accountExpires", "sAMAccountType"
    );

    // ══════════════════════════════════════════════════════════════════════
    //  Discover
    // ══════════════════════════════════════════════════════════════════════

    public DiscoveryProposalResponse discover(UUID directoryId, DiscoveryRequest request) {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new EntityNotFoundException("Directory not found"));

        String rootDn = request.rootDn() != null ? request.rootDn() : dc.getBaseDn();
        int sampleSize = request.effectiveSampleSize();
        List<String> warnings = new ArrayList<>();

        // Step 1 — Discover user OUs
        List<DiscoveredOU> userOUs = discoverUserOUs(dc, rootDn, sampleSize, warnings);

        if (userOUs.isEmpty()) {
            warnings.add("No user entries found under " + rootDn +
                    ". Check the base DN configuration or try a different root DN.");
        }

        if (userOUs.size() > 50) {
            warnings.add("Large directory detected (" + userOUs.size() +
                    " OUs) — consider narrowing the root DN for more targeted discovery.");
        }

        // Step 2 — Discover groups
        List<DiscoveredGroupOU> groupOUs = new ArrayList<>();
        List<DiscoveredGroup> groups = new ArrayList<>();
        if (request.effectiveIncludeGroups()) {
            discoverGroups(dc, rootDn, groupOUs, groups, warnings);
        }

        // Step 3 — Check which OUs already have profiles
        List<ProvisioningProfile> existingProfiles =
                profileRepo.findAllByDirectoryIdOrderByNameAsc(directoryId);
        Set<String> existingOuDns = existingProfiles.stream()
                .map(p -> p.getTargetOuDn().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // Step 4 — Build proposed profiles with attribute configs
        List<ProposedProfile> profiles = new ArrayList<>();
        for (DiscoveredOU ou : userOUs) {
            boolean alreadyConfigured = existingOuDns.contains(ou.dn().toLowerCase(Locale.ROOT));

            List<InferredAttributeConfig> attrConfigs = inferAttributeConfigs(
                    dc, ou.objectClasses(), ou.populatedAttributes(), warnings);

            // Step 5 — Cross-reference groups with this OU
            List<DiscoveredGroupLink> groupLinks = new ArrayList<>();
            if (request.effectiveIncludeGroups() && ou.userCount() > 0) {
                groupLinks = crossReferenceGroups(dc, groups, ou, sampleSize);
            }

            profiles.add(new ProposedProfile(
                    ou.name(),
                    ou.dn(),
                    ou.objectClasses(),
                    ou.rdnAttribute(),
                    attrConfigs,
                    groupLinks,
                    ou.userCount(),
                    alreadyConfigured));
        }

        return new DiscoveryProposalResponse(directoryId, profiles, groupOUs, groups, warnings);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Commit
    // ══════════════════════════════════════════════════════════════════════

    @Transactional
    public CommitDiscoveryResponse commit(UUID directoryId, CommitDiscoveryRequest request) {
        DirectoryConnection dc = dirRepo.findById(directoryId)
                .orElseThrow(() -> new EntityNotFoundException("Directory not found"));

        List<String> warnings = new ArrayList<>();
        int profilesCreated = 0;

        // Create profiles (skipping duplicates)
        if (request.profiles() != null) {
            for (CreateProfileRequest profileReq : request.profiles()) {
                if (profileRepo.existsByDirectoryIdAndName(directoryId, profileReq.name())) {
                    warnings.add("Skipped profile '" + profileReq.name() +
                            "' — a profile with this name already exists.");
                    continue;
                }
                try {
                    profileService.create(directoryId, profileReq);
                    profilesCreated++;
                } catch (Exception e) {
                    warnings.add("Failed to create profile '" + profileReq.name() + "': " + e.getMessage());
                }
            }
        }

        // Add user base DNs (skipping duplicates)
        int userBaseDnsAdded = 0;
        if (request.userBaseDns() != null) {
            List<DirectoryUserBaseDn> existing =
                    userBaseDnRepo.findAllByDirectoryIdOrderByDisplayOrderAsc(directoryId);
            Set<String> existingDns = existing.stream()
                    .map(b -> b.getDn().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            int nextOrder = existing.stream().mapToInt(DirectoryUserBaseDn::getDisplayOrder).max().orElse(-1) + 1;

            for (String dn : request.userBaseDns()) {
                if (existingDns.contains(dn.toLowerCase(Locale.ROOT))) {
                    warnings.add("Skipped user base DN '" + dn + "' — already configured.");
                    continue;
                }
                var baseDn = new DirectoryUserBaseDn();
                baseDn.setDirectory(dc);
                baseDn.setDn(dn);
                baseDn.setDisplayOrder(nextOrder++);
                baseDn.setEditable(true);
                userBaseDnRepo.save(baseDn);
                userBaseDnsAdded++;
            }
        }

        // Add group base DNs (skipping duplicates)
        int groupBaseDnsAdded = 0;
        if (request.groupBaseDns() != null) {
            List<DirectoryGroupBaseDn> existing =
                    groupBaseDnRepo.findAllByDirectoryIdOrderByDisplayOrderAsc(directoryId);
            Set<String> existingDns = existing.stream()
                    .map(b -> b.getDn().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            int nextOrder = existing.stream().mapToInt(DirectoryGroupBaseDn::getDisplayOrder).max().orElse(-1) + 1;

            for (String dn : request.groupBaseDns()) {
                if (existingDns.contains(dn.toLowerCase(Locale.ROOT))) {
                    warnings.add("Skipped group base DN '" + dn + "' — already configured.");
                    continue;
                }
                var baseDn = new DirectoryGroupBaseDn();
                baseDn.setDirectory(dc);
                baseDn.setDn(dn);
                baseDn.setDisplayOrder(nextOrder++);
                groupBaseDnRepo.save(baseDn);
                groupBaseDnsAdded++;
            }
        }

        return new CommitDiscoveryResponse(profilesCreated, userBaseDnsAdded, groupBaseDnsAdded, warnings);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private — OU discovery
    // ══════════════════════════════════════════════════════════════════════

    /** Internal record for intermediate OU state before building the proposal. */
    private record DiscoveredOU(
            String dn, String name, int userCount,
            List<String> objectClasses, String rdnAttribute,
            Set<String> populatedAttributes) {}

    private List<DiscoveredOU> discoverUserOUs(DirectoryConnection dc, String rootDn,
                                                int sampleSize, List<String> warnings) {
        List<DiscoveredOU> result = new ArrayList<>();
        findUserOUsRecursive(dc, rootDn, sampleSize, result, warnings, 0);
        return result;
    }

    private void findUserOUsRecursive(DirectoryConnection dc, String dn, int sampleSize,
                                       List<DiscoveredOU> result, List<String> warnings, int depth) {
        if (depth > 10) return; // safety limit

        // Probe for person entries under this DN
        try {
            var users = browseService.searchEntries(dc, dn, SearchScope.ONE,
                    PERSON_FILTER, List.of("1.1"), 1);
            if (!users.isEmpty()) {
                // This DN contains users — sample it
                DiscoveredOU ou = sampleOU(dc, dn, sampleSize, warnings);
                if (ou != null) result.add(ou);
                return;
            }
        } catch (Exception e) {
            log.debug("Probe failed for {}: {}", dn, e.getMessage());
        }

        // No users here — recurse into child OUs
        try {
            var children = browseService.searchEntries(dc, dn, SearchScope.ONE,
                    OU_FILTER, List.of("1.1"), 200);
            for (SearchEntry child : children) {
                findUserOUsRecursive(dc, child.dn(), sampleSize, result, warnings, depth + 1);
            }
        } catch (Exception e) {
            warnings.add("Failed to enumerate OUs under " + dn + ": " + e.getMessage());
        }
    }

    private DiscoveredOU sampleOU(DirectoryConnection dc, String dn, int sampleSize,
                                   List<String> warnings) {
        try {
            // Count users (capped at 1001)
            var countEntries = browseService.searchEntries(dc, dn, SearchScope.ONE,
                    PERSON_FILTER, List.of("1.1"), 1001);
            int userCount = countEntries.size();

            // Sample entries for attribute analysis
            var samples = browseService.searchEntries(dc, dn, SearchScope.ONE,
                    PERSON_FILTER, List.of("*"), sampleSize);

            if (samples.isEmpty()) return null;

            // Infer objectClasses from first sample
            List<String> objectClasses = samples.get(0).attributes()
                    .getOrDefault("objectClass", samples.get(0).attributes()
                    .getOrDefault("objectclass", List.of()));
            // Filter to structural/auxiliary classes (exclude 'top')
            objectClasses = objectClasses.stream()
                    .filter(oc -> !"top".equalsIgnoreCase(oc))
                    .collect(Collectors.toList());

            // Infer RDN attribute from sampled DNs
            String rdnAttribute = inferRdnAttribute(samples);

            // Collect populated attribute names across all samples
            Set<String> populatedAttrs = new HashSet<>();
            for (SearchEntry entry : samples) {
                populatedAttrs.addAll(entry.attributes().keySet());
            }
            // Normalize to lowercase
            populatedAttrs = populatedAttrs.stream()
                    .map(a -> a.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());

            // Parse OU name from DN
            String name = parseRdnValue(dn);

            return new DiscoveredOU(dn, name, userCount, objectClasses, rdnAttribute, populatedAttrs);
        } catch (Exception e) {
            warnings.add("Failed to sample OU " + dn + ": " + e.getMessage());
            return null;
        }
    }

    private String inferRdnAttribute(List<SearchEntry> samples) {
        Map<String, Integer> rdnCounts = new HashMap<>();
        for (SearchEntry entry : samples) {
            String rdn = entry.dn().split(",")[0];
            String attr = rdn.split("=")[0].trim();
            rdnCounts.merge(attr, 1, Integer::sum);
        }
        return rdnCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("cn");
    }

    private String parseRdnValue(String dn) {
        if (dn == null || dn.isEmpty()) return "Unknown";
        String rdn = dn.split(",")[0];
        int eq = rdn.indexOf('=');
        if (eq >= 0 && eq < rdn.length() - 1) {
            return rdn.substring(eq + 1).trim();
        }
        return rdn;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private — Group discovery
    // ══════════════════════════════════════════════════════════════════════

    private void discoverGroups(DirectoryConnection dc, String rootDn,
                                 List<DiscoveredGroupOU> groupOUs,
                                 List<DiscoveredGroup> groups,
                                 List<String> warnings) {
        try {
            List<LdapGroup> ldapGroups = groupService.searchGroups(dc, GROUP_FILTER, rootDn,
                    500, "cn", "member", "uniqueMember", "memberUid", "description");

            // Collect parent DNs as group OUs
            Map<String, List<LdapGroup>> byParent = new LinkedHashMap<>();
            for (LdapGroup g : ldapGroups) {
                String parentDn = extractParentDn(g.getDn());
                byParent.computeIfAbsent(parentDn, k -> new ArrayList<>()).add(g);
            }

            for (var entry : byParent.entrySet()) {
                groupOUs.add(new DiscoveredGroupOU(
                        entry.getKey(),
                        parseRdnValue(entry.getKey()),
                        entry.getValue().size()));
            }

            // Build discovered group list
            for (LdapGroup g : ldapGroups) {
                String memberAttr = detectMemberAttribute(g);
                int memberCount = g.getAllMembers().size();
                groups.add(new DiscoveredGroup(
                        g.getDn(),
                        g.getCn(),
                        memberAttr,
                        memberCount));
            }
        } catch (Exception e) {
            warnings.add("Failed to discover groups: " + e.getMessage());
        }
    }

    private String detectMemberAttribute(LdapGroup g) {
        if (!g.getMember().isEmpty()) return "member";
        if (!g.getUniqueMember().isEmpty()) return "uniqueMember";
        if (!g.getMemberUid().isEmpty()) return "memberUid";
        return "member"; // default
    }

    private String extractParentDn(String dn) {
        int comma = dn.indexOf(',');
        if (comma >= 0 && comma < dn.length() - 1) {
            return dn.substring(comma + 1);
        }
        return dn;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private — Attribute config inference
    // ══════════════════════════════════════════════════════════════════════

    private List<InferredAttributeConfig> inferAttributeConfigs(
            DirectoryConnection dc, List<String> objectClasses,
            Set<String> populatedAttributes, List<String> warnings) {

        List<InferredAttributeConfig> configs = new ArrayList<>();

        try {
            ObjectClassAttributes merged = schemaService.getAttributesForObjectClasses(dc, objectClasses);
            Set<String> required = merged.required();
            Set<String> optional = merged.optional();

            Set<String> allAttrs = new LinkedHashSet<>(required);
            allAttrs.addAll(optional);

            for (String attrName : allAttrs) {
                String lower = attrName.toLowerCase(Locale.ROOT);

                // Skip system attributes
                if (SYSTEM_ATTRIBUTES.contains(lower)) continue;

                // Check name-based overrides
                String nameOverride = ATTR_NAME_OVERRIDES.get(lower);
                if ("SKIP".equals(nameOverride)) continue;

                // Get schema info
                String inputType = "TEXT";
                boolean singleValued = true;
                String syntaxOid = null;

                try {
                    AttributeTypeInfo info = schemaService.getAttributeTypeInfo(dc, attrName);
                    singleValued = info.singleValued();
                    syntaxOid = info.syntaxOid();

                    if (syntaxOid != null && SKIP_SYNTAXES.contains(syntaxOid)) continue;

                    if (nameOverride != null) {
                        inputType = nameOverride;
                    } else if (syntaxOid != null && SYNTAX_INPUT_TYPE_MAP.containsKey(syntaxOid)) {
                        inputType = SYNTAX_INPUT_TYPE_MAP.get(syntaxOid);
                    }
                } catch (Exception e) {
                    log.debug("Could not get schema info for attribute {}: {}", attrName, e.getMessage());
                }

                // Multi-value detection
                boolean multiValued = !singleValued;
                if (multiValued && "TEXT".equals(inputType)) {
                    inputType = "MULTI_VALUE";
                }

                boolean isRequired = required.contains(attrName);
                boolean isPopulated = populatedAttributes.contains(lower);

                configs.add(new InferredAttributeConfig(
                        attrName,
                        humanizeAttributeName(attrName),
                        inputType,
                        isRequired,
                        !isPopulated,  // hidden if not populated
                        multiValued,
                        syntaxOid));
            }
        } catch (Exception e) {
            warnings.add("Failed to infer attribute configs from schema: " + e.getMessage());
        }

        return configs;
    }

    private String humanizeAttributeName(String name) {
        // "telephoneNumber" → "Telephone Number"
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append(' ');
            }
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private — Group cross-referencing
    // ══════════════════════════════════════════════════════════════════════

    private List<DiscoveredGroupLink> crossReferenceGroups(
            DirectoryConnection dc, List<DiscoveredGroup> groups,
            DiscoveredOU ou, int sampleLimit) {

        List<DiscoveredGroupLink> links = new ArrayList<>();
        String ouDnLower = ou.dn().toLowerCase(Locale.ROOT);

        for (DiscoveredGroup group : groups) {
            if (group.memberCount() == 0) continue;

            try {
                // Sample members (up to 50)
                List<String> members = groupService.getMembers(dc, group.dn(), group.memberAttribute());
                int limit = Math.min(members.size(), 50);
                List<String> sampled = members.subList(0, limit);

                // Count overlap with this OU
                int overlapCount = 0;
                for (String memberDn : sampled) {
                    if (memberDn.toLowerCase(Locale.ROOT).endsWith("," + ouDnLower)) {
                        overlapCount++;
                    }
                }

                if (overlapCount > 0) {
                    // Extrapolate if we sampled a subset
                    double overlapPercent = (overlapCount * 100.0) / limit;
                    int estimatedOverlap = (members.size() == limit) ? overlapCount
                            : (int) Math.round(overlapPercent * ou.userCount() / 100.0);

                    links.add(new DiscoveredGroupLink(
                            group.dn(), group.cn(), group.memberAttribute(),
                            estimatedOverlap,
                            Math.min(100.0, Math.round(overlapPercent * 10) / 10.0)));
                }
            } catch (Exception e) {
                log.debug("Failed to cross-reference group {} with OU {}: {}",
                        group.dn(), ou.dn(), e.getMessage());
            }
        }

        // Sort by overlap descending
        links.sort(Comparator.comparingDouble(DiscoveredGroupLink::overlapPercent).reversed());
        return links;
    }
}
