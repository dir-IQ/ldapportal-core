// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.unboundid.ldap.sdk.schema.AttributeSyntaxDefinition;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Discovers the LDAP schema (objectClasses and attributeTypes) from the
 * directory server's subschema subentry.
 *
 * <p>The schema is fetched on each call — caching is intentionally left to
 * the REST layer (Phase 3) where it can be tied to cache eviction events
 * (e.g. user-triggered "refresh schema").</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LdapSchemaService {

    private final LdapConnectionFactory connectionFactory;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns all objectClass names defined in the directory schema,
     * sorted alphabetically.
     */
    public List<SchemaListItem> getObjectClassNames(DirectoryConnection dc) {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("This feature is not supported for Entra ID directories");
        }
        return objectClassItems(fetchSchema(dc));
    }

    /**
     * Map the schema's objectClass definitions to sorted, name-deduplicated
     * list items. Package-private + static so it's unit-testable against a
     * hand-built {@link Schema} without a live directory connection.
     */
    static List<SchemaListItem> objectClassItems(Schema schema) {
        return schema.getObjectClasses().stream()
            .map(ocd -> new SchemaListItem(ocd.getNameOrOID(), ocd.getOID()))
            .sorted(Comparator.comparing(SchemaListItem::name, String.CASE_INSENSITIVE_ORDER))
            .filter(distinctByKey(i -> i.name().toLowerCase(Locale.ROOT)))
            .collect(Collectors.toList());
    }

    /**
     * Returns all attributeType definitions in the directory schema, sorted
     * alphabetically by name. Includes {@code syntaxOid} and
     * {@code singleValued} on every entry — the inline-edit results table
     * needs both to decide which cells render as editable inputs vs.
     * chip editors (a Phase 1.5 feature). Trades a few bytes per attribute
     * over the wire for an N+1 round trip we'd otherwise need.
     */
    /**
     * Stateful de-dup predicate that keeps the first element seen for each key.
     * Some directories — notably OUD — return the same schema element more than
     * once (e.g. an objectClass whose definition is present in several schema
     * files), so the raw definition list can carry several entries that resolve
     * to the same display name. Collapsing them keeps the browser list clean
     * and, just as importantly, keeps element names unique — the browser's list
     * is keyed by name, and duplicate keys break its reconciliation (a stale,
     * un-filterable list). Sequential stream use only.
     */
    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = new HashSet<>();
        return t -> seen.add(keyExtractor.apply(t));
    }

    public List<AttributeTypeInfo> getAttributeTypeNames(DirectoryConnection dc) {
        return attributeTypeItems(fetchSchema(dc));
    }

    /**
     * Map the schema's attributeType definitions to sorted, name-deduplicated
     * list items. Package-private + static for unit-testing (see
     * {@link #objectClassItems}).
     */
    static List<AttributeTypeInfo> attributeTypeItems(Schema schema) {
        return schema.getAttributeTypes().stream()
            .map(atd -> new AttributeTypeInfo(
                atd.getNameOrOID(),
                atd.getOID(),
                atd.getSyntaxOID(),
                atd.isSingleValued()))
            .sorted(Comparator.comparing(AttributeTypeInfo::name, String.CASE_INSENSITIVE_ORDER))
            .filter(distinctByKey(i -> i.name().toLowerCase(Locale.ROOT)))
            .collect(Collectors.toList());
    }

    /**
     * Returns the required and optional attributes for a given objectClass.
     *
     * @param dc          directory connection
     * @param objectClass exact objectClass name as returned by {@link #getObjectClassNames}
     * @return {@link ObjectClassAttributes} containing required and optional attribute names
     * @throws LdapOperationException if the objectClass does not exist in the schema
     */
    public ObjectClassAttributes getAttributesForObjectClass(DirectoryConnection dc,
                                                             String objectClass) {
        Schema schema = fetchSchema(dc);
        ObjectClassDefinition ocd = schema.getObjectClass(objectClass);
        if (ocd == null) {
            throw new LdapOperationException(
                "ObjectClass '" + objectClass + "' not found in schema for ["
                + dc.getDisplayName() + "]");
        }

        Set<String> required = collectAttributeNames(schema, ocd, true);
        Set<String> optional = collectAttributeNames(schema, ocd, false);

        return new ObjectClassAttributes(objectClass, ocd.getOID(), required, optional);
    }

    /**
     * Rich detail for a single objectClass — a superset of
     * {@link ObjectClassAttributes} adding the inheritance hierarchy: the
     * ancestor chain walked up the {@code SUP} links, the direct subclasses
     * (reverse index), and the class kind (STRUCTURAL / AUXILIARY / ABSTRACT).
     * The schema is already in memory from the same fetch, so this adds no
     * LDAP round trips.
     *
     * @throws LdapOperationException if the objectClass does not exist in the schema
     */
    public ObjectClassDetail getObjectClassDetail(DirectoryConnection dc, String objectClass) {
        Schema schema = fetchSchema(dc);
        ObjectClassDefinition ocd = schema.getObjectClass(objectClass);
        if (ocd == null) {
            throw new LdapOperationException(
                "ObjectClass '" + objectClass + "' not found in schema for ["
                + dc.getDisplayName() + "]");
        }
        return buildObjectClassDetail(schema, ocd);
    }

    /** Visible for testing: builds the detail from an already-resolved schema + class. */
    static ObjectClassDetail buildObjectClassDetail(Schema schema, ObjectClassDefinition ocd) {
        return new ObjectClassDetail(
            ocd.getNameOrOID(),
            ocd.getOID(),
            ocd.getDescription(),
            ocd.getObjectClassType() == null ? null : ocd.getObjectClassType().getName(),
            collectAttributeNames(schema, ocd, true),
            collectAttributeNames(schema, ocd, false),
            collectSuperiors(schema, ocd),
            collectSubclasses(schema, ocd));
    }

    /**
     * The ancestor chain, nearest parent first (e.g. for {@code secUser}:
     * {@code eUser}, {@code cimManagedElement}, {@code top}). LDAP permits
     * multiple {@code SUP} classes, so this is a breadth-first walk of a DAG
     * rather than a strict chain; the visited set both de-duplicates a diamond
     * and guards against a malformed cyclic schema. Names that don't resolve to
     * a definition are still reported — a dangling SUP is worth seeing.
     */
    private static List<String> collectSuperiors(Schema schema, ObjectClassDefinition ocd) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(ocd.getNameOrOID().toLowerCase(Locale.ROOT));
        Deque<ObjectClassDefinition> queue = new ArrayDeque<>();
        queue.add(ocd);
        while (!queue.isEmpty()) {
            ObjectClassDefinition current = queue.poll();
            String[] superNames = current.getSuperiorClasses();
            if (superNames == null) {
                continue;
            }
            for (String superName : superNames) {
                if (!visited.add(superName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                ObjectClassDefinition superOcd = schema.getObjectClass(superName);
                // Report the definition's canonical name when it resolves, so
                // an alias in the SUP clause still links to the right entry.
                chain.add(superOcd != null ? superOcd.getNameOrOID() : superName);
                if (superOcd != null) {
                    queue.add(superOcd);
                }
            }
        }
        return chain;
    }

    /**
     * Direct subclasses: every objectClass naming this one in its {@code SUP}
     * clause (by any alias or by OID). Sorted case-insensitively for a stable,
     * readable list. Only direct children — the browser lets you walk further
     * down by clicking through.
     */
    private static List<String> collectSubclasses(Schema schema, ObjectClassDefinition ocd) {
        Set<String> targets = new HashSet<>();
        for (String name : ocd.getNames()) {
            targets.add(name.toLowerCase(Locale.ROOT));
        }
        targets.add(ocd.getOID().toLowerCase(Locale.ROOT));

        List<String> subs = new ArrayList<>();
        for (ObjectClassDefinition candidate : schema.getObjectClasses()) {
            if (candidate.getNameOrOID().equalsIgnoreCase(ocd.getNameOrOID())) {
                continue;   // a class is not its own subclass
            }
            if (containsAny(candidate.getSuperiorClasses(), targets)) {
                subs.add(candidate.getNameOrOID());
            }
        }
        subs.sort(String.CASE_INSENSITIVE_ORDER);
        return subs.stream()
            .filter(distinctByKey(n -> n.toLowerCase(Locale.ROOT)))
            .collect(Collectors.toList());
    }

    /**
     * Returns detailed information about a specific attributeType.
     *
     * @throws LdapOperationException if the attribute does not exist in the schema
     */
    public AttributeTypeInfo getAttributeTypeInfo(DirectoryConnection dc, String attributeName) {
        Schema schema = fetchSchema(dc);
        AttributeTypeDefinition atd = schema.getAttributeType(attributeName);
        if (atd == null) {
            throw new LdapOperationException(
                "AttributeType '" + attributeName + "' not found in schema for ["
                + dc.getDisplayName() + "]");
        }
        return new AttributeTypeInfo(
            atd.getNameOrOID(),
            atd.getOID(),
            atd.getSyntaxOID(),
            atd.isSingleValued());
    }

    /**
     * Returns rich detail for a single attributeType: its resolved syntax
     * (human-readable name, following the SUP chain when the attribute doesn't
     * declare its own) and the reverse usage index — every objectClass that
     * lists the attribute as required (MUST) or optional (MAY), each flagged as
     * a direct declaration or inherited from a superclass. The schema is already
     * in memory from the same fetch, so this adds no LDAP round trips.
     *
     * @throws LdapOperationException if the attribute does not exist in the schema
     */
    public AttributeTypeDetail getAttributeTypeDetail(DirectoryConnection dc, String attributeName) {
        Schema schema = fetchSchema(dc);
        AttributeTypeDefinition atd = schema.getAttributeType(attributeName);
        if (atd == null) {
            throw new LdapOperationException(
                "AttributeType '" + attributeName + "' not found in schema for ["
                + dc.getDisplayName() + "]");
        }
        return buildAttributeDetail(schema, atd);
    }

    /** Visible for testing: builds the detail from an already-resolved schema + attribute. */
    static AttributeTypeDetail buildAttributeDetail(Schema schema, AttributeTypeDefinition atd) {
        return new AttributeTypeDetail(
            atd.getNameOrOID(),
            atd.getOID(),
            atd.getDescription(),
            atd.isSingleValued(),
            resolveSyntax(schema, atd),
            collectUsage(schema, atd));
    }

    /**
     * Reverse usage index: every objectClass whose effective attribute set
     * includes {@code atd}, classified as MUST/MAY and direct/inherited. An
     * objectClass that declares the attribute directly is "direct"; one that
     * only picks it up through a superclass is "inherited". MUST wins over MAY
     * when both apply across the inheritance chain.
     */
    private static List<AttributeUsage> collectUsage(Schema schema, AttributeTypeDefinition atd) {
        // Match on the attribute's full alias set (plus OID), lower-cased, so a
        // class referencing it by any name or by OID is found.
        Set<String> targets = new HashSet<>();
        for (String name : atd.getNames()) {
            targets.add(name.toLowerCase(Locale.ROOT));
        }
        targets.add(atd.getOID().toLowerCase(Locale.ROOT));

        List<AttributeUsage> usage = new ArrayList<>();
        for (ObjectClassDefinition ocd : schema.getObjectClasses()) {
            boolean directMust = containsAny(ocd.getRequiredAttributes(), targets);
            boolean directMay = containsAny(ocd.getOptionalAttributes(), targets);
            // Short-circuit the superclass walk when the attribute is declared
            // directly on this class.
            boolean effectiveMust = directMust || containsAny(collectAttributeNames(schema, ocd, true), targets);
            boolean effectiveMay = directMay || containsAny(collectAttributeNames(schema, ocd, false), targets);

            if (effectiveMust) {
                usage.add(new AttributeUsage(ocd.getNameOrOID(), true, !directMust));
            } else if (effectiveMay) {
                usage.add(new AttributeUsage(ocd.getNameOrOID(), false, !directMay));
            }
        }
        usage.sort(Comparator.comparing(AttributeUsage::objectClass, String.CASE_INSENSITIVE_ORDER));
        return usage;
    }

    /**
     * Resolves the attribute's syntax to {@link SyntaxInfo}: follows the SUP
     * chain when the attribute doesn't declare its own syntax, strips the
     * optional {@code {length}} suffix, and names the OID from the server's
     * published {@code ldapSyntaxes} (preferred) or the built-in catalogue.
     */
    private static SyntaxInfo resolveSyntax(Schema schema, AttributeTypeDefinition atd) {
        String raw = inheritedSyntaxOid(schema, atd);
        if (raw == null) {
            return null;
        }
        String base = raw;
        Integer maxLength = null;
        int brace = raw.indexOf('{');
        if (brace >= 0 && raw.endsWith("}")) {
            base = raw.substring(0, brace);
            try {
                maxLength = Integer.valueOf(raw.substring(brace + 1, raw.length() - 1));
            } catch (NumberFormatException ignored) {
                // Non-numeric length hint — leave maxLength null, keep the base OID.
            }
        }
        String description = serverSyntaxDescription(schema, base);
        if (description == null) {
            description = LdapSyntaxCatalog.describe(base);
        }
        return new SyntaxInfo(base, description, maxLength);
    }

    /** The attribute's syntax OID, walking the SUP chain if it doesn't declare one. */
    private static String inheritedSyntaxOid(Schema schema, AttributeTypeDefinition atd) {
        Set<String> visited = new HashSet<>();
        AttributeTypeDefinition current = atd;
        while (current != null && visited.add(current.getNameOrOID())) {
            if (current.getSyntaxOID() != null) {
                return current.getSyntaxOID();
            }
            String superior = current.getSuperiorType();
            current = (superior == null) ? null : schema.getAttributeType(superior);
        }
        return null;
    }

    /** Syntax description published by the server in {@code ldapSyntaxes}, or null. */
    private static String serverSyntaxDescription(Schema schema, String baseOid) {
        for (AttributeSyntaxDefinition def : schema.getAttributeSyntaxes()) {
            if (baseOid.equals(def.getOID())) {
                String desc = def.getDescription();
                return (desc == null || desc.isBlank()) ? null : desc;
            }
        }
        return null;
    }

    private static boolean containsAny(String[] names, Set<String> lowerTargets) {
        if (names == null) {
            return false;
        }
        for (String name : names) {
            if (lowerTargets.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(Set<String> names, Set<String> lowerTargets) {
        for (String name : names) {
            if (lowerTargets.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ── Schema fetch (shared) ─────────────────────────────────────────────────

    /**
     * Fetches the live subschema for {@code dc}. Exposed so the schema-write
     * path can diff uploaded elements against what already exists and export a
     * full snapshot; unlike the browse endpoints it does not reject Entra here —
     * callers that don't support Entra reject earlier.
     */
    public Schema fetchSchema(DirectoryConnection dc) {
        return connectionFactory.withConnection(dc, conn -> {
            try {
                Schema schema = conn.getSchema();
                if (schema == null) {
                    throw new LdapOperationException(
                        "Server did not return a schema for [" + dc.getDisplayName() + "]");
                }
                log.debug("Fetched schema from [{}]: {} objectClasses, {} attributeTypes",
                    dc.getDisplayName(),
                    schema.getObjectClasses().size(),
                    schema.getAttributeTypes().size());
                return schema;
            } catch (LdapOperationException e) {
                throw e;
            } catch (Exception e) {
                throw new LdapOperationException(
                    "Failed to fetch schema from [" + dc.getDisplayName() + "]: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Collects required or optional attribute names for an objectClass,
     * walking the superclass chain to include inherited attributes.
     */
    private static Set<String> collectAttributeNames(Schema schema,
                                                     ObjectClassDefinition ocd,
                                                     boolean required) {
        Set<String> names = new LinkedHashSet<>();
        collectRecursive(schema, ocd, required, names, new HashSet<>());
        return Collections.unmodifiableSet(names);
    }

    private static void collectRecursive(Schema schema,
                                         ObjectClassDefinition ocd,
                                         boolean required,
                                         Set<String> accumulator,
                                         Set<String> visited) {
        String key = ocd.getNameOrOID();
        if (!visited.add(key)) {
            return;
        }

        String[] attrNames = required
            ? ocd.getRequiredAttributes()
            : ocd.getOptionalAttributes();
        if (attrNames != null) {
            Collections.addAll(accumulator, attrNames);
        }

        // Walk superclasses
        String[] superNames = ocd.getSuperiorClasses();
        if (superNames != null) {
            for (String superName : superNames) {
                ObjectClassDefinition superOcd = schema.getObjectClass(superName);
                if (superOcd != null) {
                    collectRecursive(schema, superOcd, required, accumulator, visited);
                }
            }
        }
    }

    /**
     * Returns the merged required and optional attributes for multiple objectClasses.
     * Attributes required by any class are listed as required; attributes optional
     * in all classes are listed as optional.  Duplicates are collapsed.
     */
    public ObjectClassAttributes getAttributesForObjectClasses(DirectoryConnection dc,
                                                                List<String> objectClasses) {
        Set<String> allRequired = new LinkedHashSet<>();
        Set<String> allOptional = new LinkedHashSet<>();
        for (String oc : objectClasses) {
            ObjectClassAttributes attrs = getAttributesForObjectClass(dc, oc);
            allRequired.addAll(attrs.required());
            allOptional.addAll(attrs.optional());
        }
        // Anything in required should not also appear in optional
        allOptional.removeAll(allRequired);
        return new ObjectClassAttributes(
            String.join(", ", objectClasses),
            null,
            Collections.unmodifiableSet(allRequired),
            Collections.unmodifiableSet(allOptional));
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    /**
     * Name and OID for schema list entries.
     */
    public record SchemaListItem(String name, String oid) {}

    /**
     * Required and optional attribute names for an objectClass.
     */
    public record ObjectClassAttributes(
        String objectClassName,
        String oid,
        Set<String> required,
        Set<String> optional
    ) {}

    /**
     * Rich detail for a single objectClass — drives the schema browser's
     * objectClass view. A superset of {@link ObjectClassAttributes} (same
     * {@code required} / {@code optional} sets, so existing consumers of the
     * single-class endpoint keep working) plus the inheritance hierarchy.
     *
     * @param superiors ancestors, nearest parent first, walking {@code SUP}
     * @param subclasses direct children, case-insensitively sorted
     * @param kind STRUCTURAL / AUXILIARY / ABSTRACT, or null when unspecified
     */
    public record ObjectClassDetail(
        String objectClassName,
        String oid,
        String description,
        String kind,
        Set<String> required,
        Set<String> optional,
        List<String> superiors,
        List<String> subclasses
    ) {}

    /**
     * Summary metadata for a single attributeType.
     */
    public record AttributeTypeInfo(
        String name,
        String oid,
        String syntaxOid,
        boolean singleValued
    ) {}

    /**
     * One objectClass that references an attribute, and how it does so.
     */
    public record AttributeUsage(
        String objectClass,
        /** {@code true} = MUST (required); {@code false} = MAY (optional). */
        boolean required,
        /** {@code true} when inherited from a superclass rather than declared
         *  on this objectClass directly. */
        boolean inherited
    ) {}

    /**
     * Resolved attribute syntax: the OID plus a human-readable name when known
     * (from the server's {@code ldapSyntaxes} or the built-in catalogue) and an
     * optional length hint.
     */
    public record SyntaxInfo(
        String oid,
        String description,
        Integer maxLength
    ) {}

    /**
     * Rich detail for a single attributeType — drives the schema browser's
     * attribute view: resolved syntax and the reverse "used by" objectClass
     * index.
     */
    public record AttributeTypeDetail(
        String name,
        String oid,
        String description,
        boolean singleValued,
        SyntaxInfo syntax,
        List<AttributeUsage> usedBy
    ) {}
}
