// SPDX-License-Identifier: Apache-2.0
import client from './client'

/**
 * The user-preferences framework's API surface — one place that persists every
 * UI customization an account makes (theme, density, table column state, saved
 * filters, search history, modal sizes, sidebar, ...). Mirrors
 * {@code PreferencesController} on the backend.
 *
 * The document is a namespaced JSON object; the frontend owns the schema within
 * each namespace. Writes are partial: send only the namespace subtree you
 * touched and the server merge-patches it in.
 */
export type PreferencesDocument = Record<string, unknown>

/** Full preferences document for the current account. */
export const getPreferences = () =>
  client.get<PreferencesDocument>('/me/preferences')

/** Merge-patch (RFC 7386) a partial document; a `null` value deletes a key. */
export const patchPreferences = (patch: PreferencesDocument) =>
  client.patch<PreferencesDocument>('/me/preferences', patch)

/** Replace one namespace's subtree wholesale. */
export const putPreferenceNamespace = (namespace: string, value: unknown) =>
  client.put<PreferencesDocument>(`/me/preferences/${namespace}`, value)

/** Reset one namespace to defaults. */
export const deletePreferenceNamespace = (namespace: string) =>
  client.delete<PreferencesDocument>(`/me/preferences/${namespace}`)
