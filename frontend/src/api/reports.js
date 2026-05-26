// SPDX-License-Identifier: Apache-2.0
import client from './client'

const base = (dirId) => `/directories/${dirId}/report-jobs`

export const listReportJobs = (dirId, params) =>
  client.get(base(dirId), { params })

export const getReportJob = (dirId, jobId) =>
  client.get(`${base(dirId)}/${jobId}`)

export const createReportJob = (dirId, data) =>
  client.post(base(dirId), data)

export const updateReportJob = (dirId, jobId, data) =>
  client.put(`${base(dirId)}/${jobId}`, data)

export const deleteReportJob = (dirId, jobId) =>
  client.delete(`${base(dirId)}/${jobId}`)

export const setReportJobEnabled = (dirId, jobId, enabled) =>
  client.patch(`${base(dirId)}/${jobId}/enabled`, null, { params: { enabled } })

// Operational reports — served by the core ReportController; available
// in both community and commercial. CSV download only on community
// (PDF support requires the commercial governance module).
export const runOperationalReport = (dirId, data, format = 'CSV') =>
  client.post(`/directories/${dirId}/reports/run`, data,
              { params: { format }, responseType: 'blob' })

export const runOperationalReportData = (dirId, data) =>
  client.post(`/directories/${dirId}/reports/run-data`, data)

// PDF export for operational report types — served by an ee endpoint
// (the OpenPDF renderer + branding is part of the commercial bundle).
// Gated on the GOVERNANCE entitlement; the frontend hides the PDF
// button on community so this is only ever called on commercial.
export const runOperationalReportPdf = (dirId, data) =>
  client.post(`/directories/${dirId}/reports/run-pdf`, data,
              { responseType: 'blob' })

// Compliance reports — served by ee ScheduledReportJobController and
// gated on the GOVERNANCE entitlement. Operational report types are
// rejected at the controller with a 400.
export const runComplianceReport = (dirId, data) =>
  client.post(`/directories/${dirId}/compliance-reports/run`, data,
              { responseType: 'blob' })

export const runComplianceReportData = (dirId, data) =>
  client.post(`/directories/${dirId}/compliance-reports/run-data`, data)
