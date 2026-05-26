#!/bin/bash
# Delete test scenario entries (run after acmecorp-test-cleanup.ldif)
# Usage: bash acmecorp-test-cleanup-delete.sh "cn=admin,dc=acmecorp,dc=com" <password>

BIND_DN="${1:?Usage: $0 <bind-dn> <password>}"
BIND_PW="${2:?Usage: $0 <bind-dn> <password>}"
OPTS="-x -D $BIND_DN -w $BIND_PW"

# Delete entries (leaf nodes first, then containers)
ENTRIES=(
  "uid=ghost.admin,ou=Contractors,ou=People,dc=acmecorp,dc=com"
  "ou=Contractors,ou=People,dc=acmecorp,dc=com"
  "uid=vendor.integrator,ou=Vendors,ou=People,dc=acmecorp,dc=com"
  "ou=Vendors,ou=People,dc=acmecorp,dc=com"
  "uid=new.hire.emma,ou=Engineering,ou=People,dc=acmecorp,dc=com"
  "uid=new.hire.raj,ou=Finance,ou=People,dc=acmecorp,dc=com"
  "uid=new.hire.sofia,ou=Sales,ou=People,dc=acmecorp,dc=com"
  "uid=new.hire.omar,ou=Marketing,ou=People,dc=acmecorp,dc=com"
  "uid=new.hire.mei,ou=HumanResources,ou=People,dc=acmecorp,dc=com"
  "uid=missing.dept.kyle,ou=Engineering,ou=People,dc=acmecorp,dc=com"
  "uid=missing.dept.anna,ou=Finance,ou=People,dc=acmecorp,dc=com"
  "uid=missing.dept.lucas,ou=Sales,ou=People,dc=acmecorp,dc=com"
  "uid=disabled.jane,ou=Finance,ou=People,dc=acmecorp,dc=com"
  "uid=disabled.marcus,ou=Engineering,ou=People,dc=acmecorp,dc=com"
  "uid=disabled.priya,ou=HumanResources,ou=People,dc=acmecorp,dc=com"
  "uid=disabled.tom,ou=IT,ou=People,dc=acmecorp,dc=com"
  "cn=Payroll-Admin,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=Payroll-Approver,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=Vendor-Management,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=AP-Processing,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=Code-Deploy-Prod,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=Code-Review-Approver,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=HR-DataAdmin,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=Decommissioned-ProjectX,ou=Projects,ou=Groups,dc=acmecorp,dc=com"
  "cn=Temp-Audit-2025,ou=Access,ou=Groups,dc=acmecorp,dc=com"
  "cn=Migration-Staging,ou=Access,ou=Groups,dc=acmecorp,dc=com"
)

for dn in "${ENTRIES[@]}"; do
  ldapdelete $OPTS "$dn" 2>/dev/null && echo "Deleted: $dn" || echo "Skip (not found): $dn"
done

echo "Cleanup complete."
