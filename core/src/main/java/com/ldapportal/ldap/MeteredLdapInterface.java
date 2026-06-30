// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.ldapportal.observability.LdapOperationMetrics;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.BindRequest;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.CompareRequest;
import com.unboundid.ldap.sdk.CompareResult;
import com.unboundid.ldap.sdk.DeleteRequest;
import com.unboundid.ldap.sdk.DereferencePolicy;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.ExtendedRequest;
import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModifyDNRequest;
import com.unboundid.ldap.sdk.ModifyRequest;
import com.unboundid.ldap.sdk.ReadOnlyAddRequest;
import com.unboundid.ldap.sdk.ReadOnlyCompareRequest;
import com.unboundid.ldap.sdk.ReadOnlyDeleteRequest;
import com.unboundid.ldap.sdk.ReadOnlyModifyDNRequest;
import com.unboundid.ldap.sdk.ReadOnlyModifyRequest;
import com.unboundid.ldap.sdk.ReadOnlySearchRequest;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultListener;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFException;
import io.micrometer.core.instrument.Tags;

import java.util.Collection;
import java.util.List;

/**
 * {@link FullLDAPInterface} delegate that times each LDAP operation and records
 * its latency + result class via {@link LdapOperationMetrics} (Phase 1
 * observability). It issues no writes of its own — every call forwards straight
 * to the wrapped delegate; only the timing/counting is added around it.
 *
 * <p>Wrapped <em>innermost</em> (closest to the connection) by
 * {@link LdapConnectionFactory}, so when the sync-capture wrapper is also in
 * play the recorded latency is the raw server round-trip, excluding capture
 * bookkeeping. Reads and writes alike are measured; infrastructure reads
 * ({@code getRootDSE}/{@code getSchema}) and {@code close} pass through
 * untimed.</p>
 */
@LdapWriteAuthorized("Latency/result decorator: forwards every operation to the "
        + "wrapped FullLDAPInterface (issues no writes of its own) and records "
        + "per-operation timing and result class.")
final class MeteredLdapInterface implements FullLDAPInterface {

    private final FullLDAPInterface delegate;
    private final LdapOperationMetrics metrics;
    private final Tags directoryTags;

    MeteredLdapInterface(FullLDAPInterface delegate, LdapOperationMetrics metrics, Tags directoryTags) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.directoryTags = directoryTags;
    }

    // ── Timing helpers ──────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Op<T> {
        T run() throws LDAPException;
    }

    @FunctionalInterface
    private interface SearchOp<T> {
        T run() throws LDAPSearchException;
    }

    private <T> T timed(String operation, Op<T> call) throws LDAPException {
        long start = System.nanoTime();
        try {
            T result = call.run();
            metrics.record(directoryTags, operation, "success", System.nanoTime() - start);
            return result;
        } catch (LDAPException e) {
            metrics.record(directoryTags, operation,
                    LdapOperationMetrics.resultClass(e.getResultCode()), System.nanoTime() - start);
            throw e;
        }
    }

    private <T> T timedSearch(SearchOp<T> call) throws LDAPSearchException {
        long start = System.nanoTime();
        try {
            T result = call.run();
            metrics.record(directoryTags, "search", "success", System.nanoTime() - start);
            return result;
        } catch (LDAPSearchException e) {
            metrics.record(directoryTags, "search",
                    LdapOperationMetrics.resultClass(e.getResultCode()), System.nanoTime() - start);
            throw e;
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Override public RootDSE getRootDSE() throws LDAPException { return delegate.getRootDSE(); }
    @Override public Schema getSchema() throws LDAPException { return delegate.getSchema(); }
    @Override public Schema getSchema(String entryDN) throws LDAPException { return delegate.getSchema(entryDN); }

    @Override public SearchResultEntry getEntry(String dn) throws LDAPException {
        return timed("search", () -> delegate.getEntry(dn));
    }
    @Override public SearchResultEntry getEntry(String dn, String... attributes) throws LDAPException {
        return timed("search", () -> delegate.getEntry(dn, attributes));
    }

    @Override public SearchResult search(SearchRequest searchRequest) throws LDAPSearchException {
        return timedSearch(() -> delegate.search(searchRequest));
    }
    @Override public SearchResult search(ReadOnlySearchRequest searchRequest) throws LDAPSearchException {
        return timedSearch(() -> delegate.search(searchRequest));
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, String filter, String... attributes)
            throws LDAPSearchException {
        return timedSearch(() -> delegate.search(baseDN, scope, filter, attributes));
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, Filter filter, String... attributes)
            throws LDAPSearchException {
        return timedSearch(() -> delegate.search(baseDN, scope, filter, attributes));
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                         String filter, String... attributes) throws LDAPSearchException {
        return timedSearch(() -> delegate.search(srl, baseDN, scope, filter, attributes));
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                         Filter filter, String... attributes) throws LDAPSearchException {
        return timedSearch(() -> delegate.search(srl, baseDN, scope, filter, attributes));
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                         int sizeLimit, int timeLimit, boolean typesOnly,
                                         String filter, String... attributes) throws LDAPSearchException {
        return timedSearch(() ->
                delegate.search(baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter, attributes));
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                         int sizeLimit, int timeLimit, boolean typesOnly,
                                         Filter filter, String... attributes) throws LDAPSearchException {
        return timedSearch(() ->
                delegate.search(baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter, attributes));
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                         DereferencePolicy derefPolicy, int sizeLimit, int timeLimit,
                                         boolean typesOnly, String filter, String... attributes)
            throws LDAPSearchException {
        return timedSearch(() -> delegate.search(srl, baseDN, scope, derefPolicy,
                sizeLimit, timeLimit, typesOnly, filter, attributes));
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                         DereferencePolicy derefPolicy, int sizeLimit, int timeLimit,
                                         boolean typesOnly, Filter filter, String... attributes)
            throws LDAPSearchException {
        return timedSearch(() -> delegate.search(srl, baseDN, scope, derefPolicy,
                sizeLimit, timeLimit, typesOnly, filter, attributes));
    }

    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, String filter,
                                                      String... attributes) throws LDAPSearchException {
        return timedSearch(() -> delegate.searchForEntry(baseDN, scope, filter, attributes));
    }
    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, Filter filter,
                                                      String... attributes) throws LDAPSearchException {
        return timedSearch(() -> delegate.searchForEntry(baseDN, scope, filter, attributes));
    }
    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                                      int timeLimit, boolean typesOnly, String filter,
                                                      String... attributes) throws LDAPSearchException {
        return timedSearch(() ->
                delegate.searchForEntry(baseDN, scope, derefPolicy, timeLimit, typesOnly, filter, attributes));
    }
    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                                      int timeLimit, boolean typesOnly, Filter filter,
                                                      String... attributes) throws LDAPSearchException {
        return timedSearch(() ->
                delegate.searchForEntry(baseDN, scope, derefPolicy, timeLimit, typesOnly, filter, attributes));
    }
    @Override public SearchResultEntry searchForEntry(SearchRequest searchRequest) throws LDAPSearchException {
        return timedSearch(() -> delegate.searchForEntry(searchRequest));
    }
    @Override public SearchResultEntry searchForEntry(ReadOnlySearchRequest searchRequest) throws LDAPSearchException {
        return timedSearch(() -> delegate.searchForEntry(searchRequest));
    }

    @Override public CompareResult compare(String dn, String attributeName, String assertionValue)
            throws LDAPException {
        return timed("compare", () -> delegate.compare(dn, attributeName, assertionValue));
    }
    @Override public CompareResult compare(CompareRequest compareRequest) throws LDAPException {
        return timed("compare", () -> delegate.compare(compareRequest));
    }
    @Override public CompareResult compare(ReadOnlyCompareRequest compareRequest) throws LDAPException {
        return timed("compare", () -> delegate.compare(compareRequest));
    }

    // ── Writes ─────────────────────────────────────────────────────────────────

    @Override public LDAPResult add(String dn, Attribute... attributes) throws LDAPException {
        return timed("add", () -> delegate.add(dn, attributes));
    }
    @Override public LDAPResult add(String dn, Collection<Attribute> attributes) throws LDAPException {
        return timed("add", () -> delegate.add(dn, attributes));
    }
    @Override public LDAPResult add(Entry entry) throws LDAPException {
        return timed("add", () -> delegate.add(entry));
    }
    @Override public LDAPResult add(AddRequest addRequest) throws LDAPException {
        return timed("add", () -> delegate.add(addRequest));
    }
    @Override public LDAPResult add(ReadOnlyAddRequest addRequest) throws LDAPException {
        return timed("add", () -> delegate.add(addRequest));
    }
    @Override public LDAPResult add(String... ldifLines) throws LDIFException, LDAPException {
        // LDIF parse (LDIFException) is a client-side failure before the server
        // call, so it propagates untimed; only the LDAP outcome is recorded.
        long start = System.nanoTime();
        try {
            LDAPResult result = delegate.add(ldifLines);
            metrics.record(directoryTags, "add", "success", System.nanoTime() - start);
            return result;
        } catch (LDAPException e) {
            metrics.record(directoryTags, "add",
                    LdapOperationMetrics.resultClass(e.getResultCode()), System.nanoTime() - start);
            throw e;
        }
    }

    @Override public LDAPResult modify(String dn, Modification mod) throws LDAPException {
        return timed("modify", () -> delegate.modify(dn, mod));
    }
    @Override public LDAPResult modify(String dn, Modification... mods) throws LDAPException {
        return timed("modify", () -> delegate.modify(dn, mods));
    }
    @Override public LDAPResult modify(String dn, List<Modification> mods) throws LDAPException {
        return timed("modify", () -> delegate.modify(dn, mods));
    }
    @Override public LDAPResult modify(ModifyRequest modifyRequest) throws LDAPException {
        return timed("modify", () -> delegate.modify(modifyRequest));
    }
    @Override public LDAPResult modify(ReadOnlyModifyRequest modifyRequest) throws LDAPException {
        return timed("modify", () -> delegate.modify(modifyRequest));
    }
    @Override public LDAPResult modify(String... ldifModificationLines) throws LDIFException, LDAPException {
        long start = System.nanoTime();
        try {
            LDAPResult result = delegate.modify(ldifModificationLines);
            metrics.record(directoryTags, "modify", "success", System.nanoTime() - start);
            return result;
        } catch (LDAPException e) {
            metrics.record(directoryTags, "modify",
                    LdapOperationMetrics.resultClass(e.getResultCode()), System.nanoTime() - start);
            throw e;
        }
    }

    @Override public LDAPResult delete(String dn) throws LDAPException {
        return timed("delete", () -> delegate.delete(dn));
    }
    @Override public LDAPResult delete(DeleteRequest deleteRequest) throws LDAPException {
        return timed("delete", () -> delegate.delete(deleteRequest));
    }
    @Override public LDAPResult delete(ReadOnlyDeleteRequest deleteRequest) throws LDAPException {
        return timed("delete", () -> delegate.delete(deleteRequest));
    }

    @Override public LDAPResult modifyDN(String dn, String newRDN, boolean deleteOldRDN) throws LDAPException {
        return timed("modify_dn", () -> delegate.modifyDN(dn, newRDN, deleteOldRDN));
    }
    @Override public LDAPResult modifyDN(String dn, String newRDN, boolean deleteOldRDN, String newSuperiorDN)
            throws LDAPException {
        return timed("modify_dn", () -> delegate.modifyDN(dn, newRDN, deleteOldRDN, newSuperiorDN));
    }
    @Override public LDAPResult modifyDN(ModifyDNRequest req) throws LDAPException {
        return timed("modify_dn", () -> delegate.modifyDN(req));
    }
    @Override public LDAPResult modifyDN(ReadOnlyModifyDNRequest req) throws LDAPException {
        return timed("modify_dn", () -> delegate.modifyDN(req));
    }

    // ── Bind / extended / close ────────────────────────────────────────────────

    @Override public BindResult bind(String bindDN, String password) throws LDAPException {
        return timed("bind", () -> delegate.bind(bindDN, password));
    }
    @Override public BindResult bind(BindRequest bindRequest) throws LDAPException {
        return timed("bind", () -> delegate.bind(bindRequest));
    }

    @Override public ExtendedResult processExtendedOperation(String requestOID) throws LDAPException {
        return timed("extended", () -> delegate.processExtendedOperation(requestOID));
    }
    @Override public ExtendedResult processExtendedOperation(String requestOID, ASN1OctetString requestValue)
            throws LDAPException {
        return timed("extended", () -> delegate.processExtendedOperation(requestOID, requestValue));
    }
    @Override public ExtendedResult processExtendedOperation(ExtendedRequest extendedRequest) throws LDAPException {
        return timed("extended", () -> delegate.processExtendedOperation(extendedRequest));
    }

    @Override public void close() { delegate.close(); }
}
