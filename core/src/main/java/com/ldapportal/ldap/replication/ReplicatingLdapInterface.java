// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

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
import com.unboundid.ldap.sdk.LDAPConnection;
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
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultListener;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.schema.Schema;
import com.unboundid.ldif.LDIFAddChangeRecord;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFModifyChangeRecord;
import com.unboundid.ldif.LDIFReader;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link FullLDAPInterface} delegate that records every successful
 * write for replication. Reads pass through with no overhead.
 *
 * <p>Wired by {@code LdapConnectionFactory.withConnection}: an
 * {@code LDAPConnection} borrowed from the pool gets wrapped before
 * being passed to the operation lambda. On a successful add / modify
 * / delete / modifyDN the wrapper calls
 * {@link ReplicationEnqueuer#enqueue(UUID, CapturedWrite)}.
 *
 * <p>Capture happens AFTER the LDAP operation succeeds, so a thrown
 * {@code LDAPException} produces no event. The enqueue itself runs in
 * its own transaction with REQUIRES_NEW and swallows its own failures
 * — see {@link ReplicationEnqueuer}.
 *
 * <p>Implements {@code FullLDAPInterface} (a sub-interface of
 * {@code LDAPInterface}) so callers can issue extended operations
 * through the wrapper too. The LDIF-string variants of add/modify
 * ({@code add(String...)}, {@code modify(String...)}) are
 * {@code LDAPConnection}-only and not part of either interface — code
 * paths that need them route through
 * {@code LdapConnectionFactory.withConnectionUnreplicated} which
 * yields the raw {@code LDAPConnection}.
 */
public final class ReplicatingLdapInterface implements FullLDAPInterface {

    private final LDAPConnection      delegate;
    private final ReplicationEnqueuer enqueuer;
    private final UUID                sourceDirectoryId;

    public ReplicatingLdapInterface(LDAPConnection delegate,
                                    ReplicationEnqueuer enqueuer,
                                    UUID sourceDirectoryId) {
        this.delegate          = delegate;
        this.enqueuer          = enqueuer;
        this.sourceDirectoryId = sourceDirectoryId;
    }

    // ── Writes — captured ────────────────────────────────────────────────────

    @Override
    public LDAPResult add(String dn, Attribute... attributes) throws LDAPException {
        LDAPResult r = delegate.add(dn, attributes);
        captureAddIfSuccess(r, dn, Arrays.asList(attributes));
        return r;
    }

    @Override
    public LDAPResult add(String dn, Collection<Attribute> attributes) throws LDAPException {
        LDAPResult r = delegate.add(dn, attributes);
        captureAddIfSuccess(r, dn, List.copyOf(attributes));
        return r;
    }

    @Override
    public LDAPResult add(Entry entry) throws LDAPException {
        LDAPResult r = delegate.add(entry);
        captureAddIfSuccess(r, entry.getDN(), List.copyOf(entry.getAttributes()));
        return r;
    }

    @Override
    public LDAPResult add(AddRequest addRequest) throws LDAPException {
        LDAPResult r = delegate.add(addRequest);
        captureAddIfSuccess(r, addRequest.getDN(), addRequest.getAttributes());
        return r;
    }

    @Override
    public LDAPResult add(ReadOnlyAddRequest addRequest) throws LDAPException {
        LDAPResult r = delegate.add(addRequest);
        captureAddIfSuccess(r, addRequest.getDN(), addRequest.getAttributes());
        return r;
    }

    @Override
    public LDAPResult add(String... ldifLines) throws LDIFException, LDAPException {
        LDAPResult r = delegate.add(ldifLines);
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            // Re-parse the LDIF lines to recover structured attributes
            // for capture. The parse must succeed because the LDAP write
            // we just delegated would have thrown first if not — but
            // belt-and-braces, swallow a re-parse miss rather than
            // re-throw something the caller didn't expect.
            try {
                Object rec = LDIFReader.decodeChangeRecord(ldifLines);
                if (rec instanceof LDIFAddChangeRecord add) {
                    captureAddIfSuccess(r, add.getDN(),
                            List.copyOf(add.getEntryToAdd().getAttributes()));
                }
            } catch (LDIFException ignored) { /* re-parse failed — skip capture */ }
        }
        return r;
    }

    @Override
    public LDAPResult modify(String dn, Modification mod) throws LDAPException {
        LDAPResult r = delegate.modify(dn, mod);
        captureModifyIfSuccess(r, dn, List.of(mod));
        return r;
    }

    @Override
    public LDAPResult modify(String dn, Modification... mods) throws LDAPException {
        LDAPResult r = delegate.modify(dn, mods);
        captureModifyIfSuccess(r, dn, Arrays.asList(mods));
        return r;
    }

    @Override
    public LDAPResult modify(String dn, List<Modification> mods) throws LDAPException {
        LDAPResult r = delegate.modify(dn, mods);
        captureModifyIfSuccess(r, dn, List.copyOf(mods));
        return r;
    }

    @Override
    public LDAPResult modify(ModifyRequest modifyRequest) throws LDAPException {
        LDAPResult r = delegate.modify(modifyRequest);
        captureModifyIfSuccess(r, modifyRequest.getDN(), modifyRequest.getModifications());
        return r;
    }

    @Override
    public LDAPResult modify(ReadOnlyModifyRequest modifyRequest) throws LDAPException {
        LDAPResult r = delegate.modify(modifyRequest);
        captureModifyIfSuccess(r, modifyRequest.getDN(), modifyRequest.getModifications());
        return r;
    }

    @Override
    public LDAPResult modify(String... ldifModificationLines) throws LDIFException, LDAPException {
        LDAPResult r = delegate.modify(ldifModificationLines);
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            try {
                Object rec = LDIFReader.decodeChangeRecord(ldifModificationLines);
                if (rec instanceof LDIFModifyChangeRecord mod) {
                    captureModifyIfSuccess(r, mod.getDN(),
                            Arrays.asList(mod.getModifications()));
                }
            } catch (LDIFException ignored) { /* re-parse failed — skip capture */ }
        }
        return r;
    }

    @Override
    public LDAPResult delete(String dn) throws LDAPException {
        LDAPResult r = delegate.delete(dn);
        captureDeleteIfSuccess(r, dn);
        return r;
    }

    @Override
    public LDAPResult delete(DeleteRequest deleteRequest) throws LDAPException {
        LDAPResult r = delegate.delete(deleteRequest);
        captureDeleteIfSuccess(r, deleteRequest.getDN());
        return r;
    }

    @Override
    public LDAPResult delete(ReadOnlyDeleteRequest deleteRequest) throws LDAPException {
        LDAPResult r = delegate.delete(deleteRequest);
        captureDeleteIfSuccess(r, deleteRequest.getDN());
        return r;
    }

    @Override
    public LDAPResult modifyDN(String dn, String newRDN, boolean deleteOldRDN) throws LDAPException {
        LDAPResult r = delegate.modifyDN(dn, newRDN, deleteOldRDN);
        captureModifyDnIfSuccess(r, dn, newRDN, deleteOldRDN, null);
        return r;
    }

    @Override
    public LDAPResult modifyDN(String dn, String newRDN, boolean deleteOldRDN, String newSuperiorDN)
            throws LDAPException {
        LDAPResult r = delegate.modifyDN(dn, newRDN, deleteOldRDN, newSuperiorDN);
        captureModifyDnIfSuccess(r, dn, newRDN, deleteOldRDN, newSuperiorDN);
        return r;
    }

    @Override
    public LDAPResult modifyDN(ModifyDNRequest modifyDNRequest) throws LDAPException {
        LDAPResult r = delegate.modifyDN(modifyDNRequest);
        captureModifyDnIfSuccess(r, modifyDNRequest.getDN(),
                modifyDNRequest.getNewRDN(), modifyDNRequest.deleteOldRDN(),
                modifyDNRequest.getNewSuperiorDN());
        return r;
    }

    @Override
    public LDAPResult modifyDN(ReadOnlyModifyDNRequest modifyDNRequest) throws LDAPException {
        LDAPResult r = delegate.modifyDN(modifyDNRequest);
        captureModifyDnIfSuccess(r, modifyDNRequest.getDN(),
                modifyDNRequest.getNewRDN(), modifyDNRequest.deleteOldRDN(),
                modifyDNRequest.getNewSuperiorDN());
        return r;
    }

    // ── Reads / extended ops — passthrough ───────────────────────────────────

    @Override public RootDSE getRootDSE() throws LDAPException { return delegate.getRootDSE(); }
    @Override public Schema getSchema() throws LDAPException { return delegate.getSchema(); }
    @Override public Schema getSchema(String entryDN) throws LDAPException { return delegate.getSchema(entryDN); }

    @Override public SearchResultEntry getEntry(String dn) throws LDAPException { return delegate.getEntry(dn); }
    @Override public SearchResultEntry getEntry(String dn, String... attributes) throws LDAPException {
        return delegate.getEntry(dn, attributes);
    }

    @Override public SearchResult search(SearchRequest searchRequest) throws LDAPSearchException {
        return delegate.search(searchRequest);
    }
    @Override public SearchResult search(ReadOnlySearchRequest searchRequest) throws LDAPSearchException {
        return delegate.search(searchRequest);
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, String filter, String... attributes)
            throws LDAPSearchException { return delegate.search(baseDN, scope, filter, attributes); }
    @Override public SearchResult search(String baseDN, SearchScope scope, Filter filter, String... attributes)
            throws LDAPSearchException { return delegate.search(baseDN, scope, filter, attributes); }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                          String filter, String... attributes) throws LDAPSearchException {
        return delegate.search(srl, baseDN, scope, filter, attributes);
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                          Filter filter, String... attributes) throws LDAPSearchException {
        return delegate.search(srl, baseDN, scope, filter, attributes);
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                          int sizeLimit, int timeLimit, boolean typesOnly,
                                          String filter, String... attributes) throws LDAPSearchException {
        return delegate.search(baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter, attributes);
    }
    @Override public SearchResult search(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                          int sizeLimit, int timeLimit, boolean typesOnly,
                                          Filter filter, String... attributes) throws LDAPSearchException {
        return delegate.search(baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter, attributes);
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                          DereferencePolicy derefPolicy, int sizeLimit, int timeLimit,
                                          boolean typesOnly, String filter, String... attributes)
            throws LDAPSearchException {
        return delegate.search(srl, baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter, attributes);
    }
    @Override public SearchResult search(SearchResultListener srl, String baseDN, SearchScope scope,
                                          DereferencePolicy derefPolicy, int sizeLimit, int timeLimit,
                                          boolean typesOnly, Filter filter, String... attributes)
            throws LDAPSearchException {
        return delegate.search(srl, baseDN, scope, derefPolicy, sizeLimit, timeLimit, typesOnly, filter, attributes);
    }

    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, String filter,
                                                       String... attributes) throws LDAPSearchException {
        return delegate.searchForEntry(baseDN, scope, filter, attributes);
    }
    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, Filter filter,
                                                       String... attributes) throws LDAPSearchException {
        return delegate.searchForEntry(baseDN, scope, filter, attributes);
    }
    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                                       int timeLimit, boolean typesOnly, String filter,
                                                       String... attributes) throws LDAPSearchException {
        return delegate.searchForEntry(baseDN, scope, derefPolicy, timeLimit, typesOnly, filter, attributes);
    }
    @Override public SearchResultEntry searchForEntry(String baseDN, SearchScope scope, DereferencePolicy derefPolicy,
                                                       int timeLimit, boolean typesOnly, Filter filter,
                                                       String... attributes) throws LDAPSearchException {
        return delegate.searchForEntry(baseDN, scope, derefPolicy, timeLimit, typesOnly, filter, attributes);
    }
    @Override public SearchResultEntry searchForEntry(SearchRequest searchRequest) throws LDAPSearchException {
        return delegate.searchForEntry(searchRequest);
    }
    @Override public SearchResultEntry searchForEntry(ReadOnlySearchRequest searchRequest) throws LDAPSearchException {
        return delegate.searchForEntry(searchRequest);
    }

    @Override public CompareResult compare(String dn, String attributeName, String assertionValue) throws LDAPException {
        return delegate.compare(dn, attributeName, assertionValue);
    }
    @Override public CompareResult compare(CompareRequest compareRequest) throws LDAPException {
        return delegate.compare(compareRequest);
    }
    @Override public CompareResult compare(ReadOnlyCompareRequest compareRequest) throws LDAPException {
        return delegate.compare(compareRequest);
    }

    @Override public ExtendedResult processExtendedOperation(String requestOID) throws LDAPException {
        return delegate.processExtendedOperation(requestOID);
    }
    @Override public ExtendedResult processExtendedOperation(String requestOID, com.unboundid.asn1.ASN1OctetString requestValue)
            throws LDAPException {
        return delegate.processExtendedOperation(requestOID, requestValue);
    }
    @Override public ExtendedResult processExtendedOperation(ExtendedRequest extendedRequest) throws LDAPException {
        return delegate.processExtendedOperation(extendedRequest);
    }

    @Override public void close() { delegate.close(); }

    // Bind is not a write in the replication sense — never enqueue.
    @Override public BindResult bind(String bindDN, String password) throws LDAPException {
        return delegate.bind(bindDN, password);
    }
    @Override public BindResult bind(BindRequest bindRequest) throws LDAPException {
        return delegate.bind(bindRequest);
    }

    // ── Capture helpers ──────────────────────────────────────────────────────

    private void captureAddIfSuccess(LDAPResult r, String dn, List<Attribute> attrs) {
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            enqueuer.enqueue(sourceDirectoryId, CapturedWrite.add(dn, attrs));
        }
    }

    private void captureModifyIfSuccess(LDAPResult r, String dn, List<Modification> mods) {
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            enqueuer.enqueue(sourceDirectoryId, CapturedWrite.modify(dn, mods));
        }
    }

    private void captureDeleteIfSuccess(LDAPResult r, String dn) {
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            enqueuer.enqueue(sourceDirectoryId, CapturedWrite.delete(dn));
        }
    }

    private void captureModifyDnIfSuccess(LDAPResult r, String dn, String newRdn,
                                           boolean deleteOldRdn, String newSuperiorDn) {
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            enqueuer.enqueue(sourceDirectoryId,
                    CapturedWrite.modifyDn(dn, newRdn, deleteOldRdn, newSuperiorDn));
        }
    }
}
