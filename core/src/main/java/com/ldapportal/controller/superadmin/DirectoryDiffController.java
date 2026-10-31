// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.dto.PageResponse;
import com.ldapportal.dto.diff.DirectoryDiffEntryDetail;
import com.ldapportal.dto.diff.DirectoryDiffEntryResponse;
import com.ldapportal.dto.diff.DirectoryDiffLiveEntry;
import com.ldapportal.dto.diff.DirectoryDiffRequest;
import com.ldapportal.dto.diff.DirectoryDiffRunResponse;
import com.ldapportal.entity.enums.DirectoryDiffCategory;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.service.DirectoryDiffService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Directory diff: compare two directories, or two branches of one, entry by
 * entry. Superadmins holding {@link SuperadminPermission#MANAGE_DIRECTORY_DIFF}
 * only: a run reads every entry of both sides, and its results show attribute
 * values. Read-only towards the directories.
 */
@RestController
@RequestMapping("/api/v1/superadmin/directory-diffs")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiresSuperadminPermission(SuperadminPermission.MANAGE_DIRECTORY_DIFF)
@RequiredArgsConstructor
public class DirectoryDiffController {

    private final DirectoryDiffService service;

    /** Start a comparison in the background (202); follow it on {@code GET …/{id}}. */
    @PostMapping
    public ResponseEntity<DirectoryDiffRunResponse> start(@RequestBody DirectoryDiffRequest request,
                                                          @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.accepted().body(service.start(request, principal));
    }

    /** The latest comparisons, newest first. */
    @GetMapping
    public List<DirectoryDiffRunResponse> recent() {
        return service.recent();
    }

    /** A comparison: its progress while it runs, then each group's count and the attributes that differ. */
    @GetMapping("/{id}")
    public DirectoryDiffRunResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/cancel")
    public DirectoryDiffRunResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    /**
     * One page of a group ({@code SAME}, {@code DIFFERENT}, {@code ONLY_A},
     * {@code ONLY_B}); for Different, optionally only where {@code attribute}
     * differs; {@code q} matches either side's DN.
     */
    @GetMapping("/{id}/entries")
    public PageResponse<DirectoryDiffEntryResponse> entries(@PathVariable UUID id,
                                                            @RequestParam DirectoryDiffCategory category,
                                                            @RequestParam(required = false) String attribute,
                                                            @RequestParam(required = false) String q,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "50") int size) {
        return service.entries(id, category, attribute, q, page, size);
    }

    /** One result as the comparison found it, with each differing attribute's values side by side. */
    @GetMapping("/{id}/entries/{seq}")
    public DirectoryDiffEntryDetail entry(@PathVariable UUID id, @PathVariable int seq) {
        return service.entry(id, seq);
    }

    /** One result's entries read again now and compared the same way, with the equal attributes' values. */
    @GetMapping("/{id}/entries/{seq}/live")
    public DirectoryDiffLiveEntry live(@PathVariable UUID id, @PathVariable int seq) {
        return service.live(id, seq);
    }

    /** A group (every group when {@code category} is left out) as CSV: group, DN on A, DN on B, attributes, note. */
    @GetMapping(value = "/{id}/entries.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@PathVariable UUID id,
                                      @RequestParam(required = false) DirectoryDiffCategory category) {
        StringBuilder out = new StringBuilder("group,dn_a,dn_b,attributes,note\r\n");
        service.forEachEntry(id, category, e -> out.append(e.category()).append(',')
                .append(csv(e.dnA())).append(',')
                .append(csv(e.dnB())).append(',')
                .append(csv(String.join(" ", e.attributes()))).append(',')
                .append(csv(e.note())).append("\r\n"));
        String name = "directory-diff-" + id + (category != null ? "-" + category.name().toLowerCase() : "") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A CSV field: quoted when it holds a comma, quote or line break; a leading formula character neutralised. */
    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String v = value;
        if ("=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
