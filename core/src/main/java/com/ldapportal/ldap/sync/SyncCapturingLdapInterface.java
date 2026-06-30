// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
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
import com.unboundid.ldif.LDIFReader;

import java.util.Collection;
import java.util.UUID;

/**
 * {@link FullLDAPInterface} delegate that turns each successful portal write into
 * a {@code recompute(dn)} via {@link SyncWriteCaptor}. Reads pass through with no
 * overhead. Capture happens AFTER the operation succeeds, so a thrown
 * {@code LDAPException} produces no recompute.
 *
 * <p>Unlike the legacy replicating wrapper this records only the affected DN —
 * the convergent engine re-reads the source, so no payload reconstruction is
 * needed here.
 */
@LdapWriteAuthorized("SDK-level capture wrapper: passes writes through to the "
        + "delegate, then enqueues a recompute for the affected DN.")
public final class SyncCapturingLdapInterface implements FullLDAPInterface {

    private final FullLDAPInterface delegate;
    private final SyncWriteCaptor captor;
    private final UUID sourceDirectoryId;

    public SyncCapturingLdapInterface(FullLDAPInterface delegate, SyncWriteCaptor captor,
                                      UUID sourceDirectoryId) {
        this.delegate = delegate;
        this.captor = captor;
        this.sourceDirectoryId = sourceDirectoryId;
    }

    private void capture(LDAPResult r, String dn) {
        if (r != null && r.getResultCode() == ResultCode.SUCCESS) {
            captor.onWrite(sourceDirectoryId, dn);
        }
    }

    // ── Writes — captured ────────────────────────────────────────────────────

    @Override public LDAPResult add(String dn, Attribute... attributes) throws LDAPException {
        LDAPResult r = delegate.add(dn, attributes); capture(r, dn); return r;
    }
    @Override public LDAPResult add(String dn, Collection<Attribute> attributes) throws LDAPException {
        LDAPResult r = delegate.add(dn, attributes); capture(r, dn); return r;
    }
    @Override public LDAPResult add(Entry entry) throws LDAPException {
        LDAPResult r = delegate.add(entry); capture(r, entry.getDN()); return r;
    }
    @Override public LDAPResult add(AddRequest addRequest) throws LDAPException {
        LDAPResult r = delegate.add(addRequest); capture(r, addRequest.getDN()); return r;
    }
    @Override public LDAPResult add(ReadOnlyAddRequest addRequest) throws LDAPException {
        LDAPResult r = delegate.add(addRequest); capture(r, addRequest.getDN()); return r;
    }
    @Override public LDAPResult add(String... ldifLines) throws LDIFException, LDAPException {
        LDAPResult r = delegate.add(ldifLines);
        if (r != null && r.getResultCode() == ResultCode.SUCCESS
                && LDIFReader.decodeChangeRecord(ldifLines) instanceof LDIFAddChangeRecord rec) {
            capture(r, rec.getDN());
        }
        return r;
    }

    @Override public LDAPResult modify(String dn, Modification mod) throws LDAPException {
        LDAPResult r = delegate.modify(dn, mod); capture(r, dn); return r;
    }
    @Override public LDAPResult modify(String dn, Modification... mods) throws LDAPException {
        LDAPResult r = delegate.modify(dn, mods); capture(r, dn); return r;
    }
    @Override public LDAPResult modify(String dn, java.util.List<Modification> mods) throws LDAPException {
        LDAPResult r = delegate.modify(dn, mods); capture(r, dn); return r;
    }
    @Override public LDAPResult modify(ModifyRequest modifyRequest) throws LDAPException {
        LDAPResult r = delegate.modify(modifyRequest); capture(r, modifyRequest.getDN()); return r;
    }
    @Override public LDAPResult modify(ReadOnlyModifyRequest modifyRequest) throws LDAPException {
        LDAPResult r = delegate.modify(modifyRequest); capture(r, modifyRequest.getDN()); return r;
    }
    @Override public LDAPResult modify(String... ldifModificationLines) throws LDIFException, LDAPException {
        LDAPResult r = delegate.modify(ldifModificationLines);
        if (r != null && r.getResultCode() == ResultCode.SUCCESS
                && LDIFReader.decodeChangeRecord(ldifModificationLines)
                        instanceof com.unboundid.ldif.LDIFModifyChangeRecord rec) {
            capture(r, rec.getDN());
        }
        return r;
    }

    @Override public LDAPResult delete(String dn) throws LDAPException {
        LDAPResult r = delegate.delete(dn); capture(r, dn); return r;
    }
    @Override public LDAPResult delete(DeleteRequest deleteRequest) throws LDAPException {
        LDAPResult r = delegate.delete(deleteRequest); capture(r, deleteRequest.getDN()); return r;
    }
    @Override public LDAPResult delete(ReadOnlyDeleteRequest deleteRequest) throws LDAPException {
        LDAPResult r = delegate.delete(deleteRequest); capture(r, deleteRequest.getDN()); return r;
    }

    @Override public LDAPResult modifyDN(String dn, String newRDN, boolean deleteOldRDN) throws LDAPException {
        LDAPResult r = delegate.modifyDN(dn, newRDN, deleteOldRDN);
        capture(r, SyncDnUtil.afterModifyDn(dn, newRDN, null));
        return r;
    }
    @Override public LDAPResult modifyDN(String dn, String newRDN, boolean deleteOldRDN, String newSuperiorDN)
            throws LDAPException {
        LDAPResult r = delegate.modifyDN(dn, newRDN, deleteOldRDN, newSuperiorDN);
        capture(r, SyncDnUtil.afterModifyDn(dn, newRDN, newSuperiorDN));
        return r;
    }
    @Override public LDAPResult modifyDN(ModifyDNRequest req) throws LDAPException {
        LDAPResult r = delegate.modifyDN(req);
        capture(r, SyncDnUtil.afterModifyDn(req.getDN(), req.getNewRDN(), req.getNewSuperiorDN()));
        return r;
    }
    @Override public LDAPResult modifyDN(ReadOnlyModifyDNRequest req) throws LDAPException {
        LDAPResult r = delegate.modifyDN(req);
        capture(r, SyncDnUtil.afterModifyDn(req.getDN(), req.getNewRDN(), req.getNewSuperiorDN()));
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
    @Override public CompareResult compare(String dn, String attributeName, String assertionValue)
            throws LDAPException { return delegate.compare(dn, attributeName, assertionValue); }
    @Override public CompareResult compare(CompareRequest compareRequest) throws LDAPException {
        return delegate.compare(compareRequest);
    }
    @Override public CompareResult compare(ReadOnlyCompareRequest compareRequest) throws LDAPException {
        return delegate.compare(compareRequest);
    }
    @Override public ExtendedResult processExtendedOperation(String requestOID) throws LDAPException {
        return delegate.processExtendedOperation(requestOID);
    }
    @Override public ExtendedResult processExtendedOperation(String requestOID, ASN1OctetString requestValue)
            throws LDAPException { return delegate.processExtendedOperation(requestOID, requestValue); }
    @Override public ExtendedResult processExtendedOperation(ExtendedRequest extendedRequest) throws LDAPException {
        return delegate.processExtendedOperation(extendedRequest);
    }
    @Override public void close() { delegate.close(); }
    @Override public BindResult bind(String bindDN, String password) throws LDAPException {
        return delegate.bind(bindDN, password);
    }
    @Override public BindResult bind(BindRequest bindRequest) throws LDAPException {
        return delegate.bind(bindRequest);
    }
}
