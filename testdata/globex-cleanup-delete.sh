#!/bin/bash
# Delete demo-persona entries created by globex-acquisition-overlaps.ldif.
# Run AFTER globex-cleanup.ldif (which removes their group memberships first).
#
# Usage: bash globex-cleanup-delete.sh "cn=admin,dc=globex,dc=com" <password>
#
# To wipe the entire Globex tree (bulk + scenarios + structure), use instead:
#   ldapdelete -x -D "cn=admin,dc=globex,dc=com" -w <pw> -r dc=globex,dc=com

BIND_DN="${1:?Usage: $0 <bind-dn> <password>}"
BIND_PW="${2:?Usage: $0 <bind-dn> <password>}"
OPTS="-x -D $BIND_DN -w $BIND_PW"

ENTRIES=(
  "uid=glx-demo-exact,ou=Engineering,ou=People,dc=globex,dc=com"
  "uid=glx-demo-high,ou=Engineering,ou=People,dc=globex,dc=com"
  "uid=glx-demo-ambig-1,ou=Engineering,ou=People,dc=globex,dc=com"
  "uid=glx-demo-ambig-2,ou=Engineering,ou=People,dc=globex,dc=com"
  "uid=glx-demo-orphan,ou=Sales,ou=People,dc=globex,dc=com"
  "uid=glx-demo-offboard,ou=Finance,ou=People,dc=globex,dc=com"
)

for dn in "${ENTRIES[@]}"; do
  ldapdelete $OPTS "$dn" 2>/dev/null && echo "Deleted: $dn" || echo "Skip (not found): $dn"
done

echo "Globex demo-persona cleanup complete."
