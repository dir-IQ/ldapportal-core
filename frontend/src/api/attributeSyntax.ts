// SPDX-License-Identifier: Apache-2.0
import client from './client'

// Built-in attribute-syntax hints (DN / email / boolean) the server enforces on
// write, so the admin forms can mirror them for instant field-level feedback.
// See AttributeSyntaxController on the backend. Admin-scoped (SUPERADMIN/ADMIN).
export const getAttributeSyntaxHints = () => client.get('/attribute-syntax')
