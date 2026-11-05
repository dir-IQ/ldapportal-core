// SPDX-License-Identifier: Apache-2.0
/**
 * Component tests for SmtpSection: required-field markers on host and sender
 * address, and the "Send Test Email" control (emits the recipient, blocked
 * while SMTP edits are unsaved, renders the server's verdict).
 */
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SmtpSection from './SmtpSection.vue'

interface SmtpForm {
  smtpHost: string
  smtpPort: number
  smtpSenderAddress: string
  smtpUsername: string
  smtpPassword: string | null
  smtpUseTls: boolean
}

function form(): SmtpForm {
  return {
    smtpHost: 'smtp.example.com',
    smtpPort: 587,
    smtpSenderAddress: 'noreply@example.com',
    smtpUsername: '',
    smtpPassword: null,
    smtpUseTls: true,
  }
}

function labelFor(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('label').find((l) => l.text().startsWith(text))
}

function testButton(wrapper: ReturnType<typeof mount>) {
  const btn = wrapper.findAll('button').find((b) => b.text().includes('Test Email') || b.text().includes('Sending'))
  if (!btn) throw new Error('test button not found')
  return btn
}

describe('SmtpSection', () => {
  it('marks host and sender address as required, credentials as optional', () => {
    const wrapper = mount(SmtpSection, { props: { form: form() } })

    expect(labelFor(wrapper, 'SMTP Host')?.text()).toContain('*')
    expect(labelFor(wrapper, 'Sender Address')?.text()).toContain('*')
    expect(labelFor(wrapper, 'Username')?.text()).not.toContain('*')
    expect(labelFor(wrapper, 'Password')?.text()).not.toContain('*')
    expect(labelFor(wrapper, 'SMTP Port')?.text()).not.toContain('*')
  })

  it('emits test with the trimmed recipient, pre-filled from defaultRecipient', async () => {
    const wrapper = mount(SmtpSection, {
      props: { form: form(), defaultRecipient: 'admin@example.com' },
    })

    await testButton(wrapper).trigger('click')
    expect(wrapper.emitted('test')).toEqual([['admin@example.com']])

    const recipient = wrapper.find('input[type="email"][placeholder="you@example.com"]')
    await recipient.setValue('  ops@example.com ')
    await testButton(wrapper).trigger('click')
    expect(wrapper.emitted('test')?.[1]).toEqual(['ops@example.com'])
  })

  it('disables the test while there are unsaved SMTP changes', () => {
    const wrapper = mount(SmtpSection, {
      props: { form: form(), defaultRecipient: 'admin@example.com', dirty: true },
    })

    expect(testButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Save your changes first')
  })

  it('disables the test without a recipient', () => {
    const wrapper = mount(SmtpSection, { props: { form: form() } })

    expect(testButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('renders the test result', () => {
    const wrapper = mount(SmtpSection, {
      props: {
        form: form(),
        testResult: { ok: false, message: 'SMTP server rejected STARTTLS: 454 TLS not available' },
      },
    })

    const status = wrapper.find('[role="status"]')
    expect(status.text()).toBe('SMTP server rejected STARTTLS: 454 TLS not available')
    expect(status.classes()).toContain('text-red-600')
  })
})
