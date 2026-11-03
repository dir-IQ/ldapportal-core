// SPDX-License-Identifier: Apache-2.0
// Shared test fixtures for the directory diff specs.
import type { DirectoryDiffRun } from '@/api/directoryDiff'

export function diffRun(overrides: Partial<DirectoryDiffRun> = {}): DirectoryDiffRun {
  return {
    id: 'run-1',
    status: 'COMPLETE',
    phase: 'DONE',
    processed: 10,
    total: 10,
    currentDn: null,
    error: null,
    requestedBy: 'sanjay',
    startedAt: '2026-10-31T14:02:00Z',
    heartbeatAt: '2026-10-31T14:05:00Z',
    finishedAt: '2026-10-31T14:05:00Z',
    a: { directoryId: 'dir-a', directoryName: 'East', baseDn: 'ou=people,dc=a', scope: 'SUB', filter: '(objectClass=*)' },
    b: { directoryId: 'dir-b', directoryName: 'West', baseDn: 'ou=Users,dc=b', scope: 'SUB', filter: '(objectClass=*)' },
    pairBy: 'DN',
    pairAttribute: null,
    attributes: [],
    ignore: [],
    counts: { same: 5, different: 2, onlyA: 1, onlyB: 2 },
    attributeCounts: [{ name: 'cn', count: 2 }, { name: 'mail', count: 1 }],
    ...overrides,
  }
}
