// SPDX-License-Identifier: Apache-2.0
import client from './client'

export const getNotifications = (params) =>
  client.get('/notifications', { params })

export const getUnreadCount = () =>
  client.get('/notifications/unread-count')

export const markRead = (id) =>
  client.post(`/notifications/${id}/read`)

export const markAllRead = () =>
  client.post('/notifications/mark-all-read')
