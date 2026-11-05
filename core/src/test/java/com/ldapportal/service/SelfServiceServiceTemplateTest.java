// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProfileAttributeConfig;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.InputType;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProfileAttributeConfigRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.repository.RegistrationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * The self-service form's field order comes from the profile's self-service
 * layout, which is ordered independently of the admin form.
 */
@ExtendWith(MockitoExtension.class)
class SelfServiceServiceTemplateTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private ProvisioningProfileRepository profileRepo;
    @Mock private ProfileAttributeConfigRepository attrConfigRepo;
    @Mock private RegistrationRequestRepository registrationRepo;
    @Mock private LdapUserService ldapUserService;
    @Mock private LdapGroupService ldapGroupService;
    @Mock private LdapConnectionFactory connectionFactory;
    @Mock private ProvisioningProfileService profileService;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private ApprovalNotificationService notificationService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private SelfServiceService service;

    private static final UUID DIR_ID = UUID.randomUUID();
    private static final String USER_DN = "uid=jdoe,ou=People,dc=example,dc=com";

    private List<String> templateFieldOrder(List<ProfileAttributeConfig> configsInAdminOrder) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(DIR_ID);
        ProvisioningProfile profile = new ProvisioningProfile();
        profile.setId(UUID.randomUUID());
        profile.setName("engineers");
        given(dirRepo.findById(DIR_ID)).willReturn(Optional.of(dc));
        given(profileService.resolveProfileForDn(DIR_ID, USER_DN)).willReturn(Optional.of(profile));
        given(attrConfigRepo.findAllByProfileIdOrderByDisplayOrderAsc(profile.getId()))
                .willReturn(configsInAdminOrder);

        AuthPrincipal principal = new AuthPrincipal(
                PrincipalType.SELF_SERVICE, UUID.randomUUID(), "jdoe", USER_DN, DIR_ID);
        return service.getTemplate(principal).fields().stream()
                .map(SelfServiceService.SelfServiceFieldConfig::attributeName)
                .toList();
    }

    private static ProfileAttributeConfig selfServiceField(String name, int adminOrder, Integer selfServiceOrder) {
        ProfileAttributeConfig c = new ProfileAttributeConfig();
        c.setAttributeName(name);
        c.setInputType(InputType.TEXT);
        c.setSelfServiceEdit(true);
        c.setColumnSpan(6);
        c.setDisplayOrder(adminOrder);
        c.setSelfServiceDisplayOrder(selfServiceOrder);
        return c;
    }

    @Test
    void getTemplate_ordersFieldsBySelfServiceLayout() {
        List<String> order = templateFieldOrder(List.of(
                selfServiceField("mail", 0, 2),
                selfServiceField("telephoneNumber", 1, 0),
                selfServiceField("mobile", 2, 1)));

        assertThat(order).containsExactly("telephoneNumber", "mobile", "mail");
    }

    @Test
    void getTemplate_uncustomisedLayout_keepsAdminOrderInsteadOfFailing() {
        // A profile whose self-service layout was never touched stores no
        // self-service order; the template used to NPE sorting it.
        List<String> order = templateFieldOrder(List.of(
                selfServiceField("mail", 0, null),
                selfServiceField("telephoneNumber", 1, null)));

        assertThat(order).containsExactly("mail", "telephoneNumber");
    }

    @Test
    void getTemplate_fieldAddedAfterCustomising_goesLast() {
        List<String> order = templateFieldOrder(List.of(
                selfServiceField("mail", 0, 1),
                selfServiceField("pager", 1, null),
                selfServiceField("mobile", 2, 0)));

        assertThat(order).containsExactly("mobile", "mail", "pager");
    }
}
