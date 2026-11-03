// SPDX-License-Identifier: Apache-2.0
/**
 * Minimal typings for the parts of sortablejs used directly (ResultsTable's
 * column picker). The package ships no types and @types/sortablejs isn't a
 * dependency; widen this as more of the API gets used.
 */
declare module 'sortablejs' {
  export interface SortableEvent {
    item: HTMLElement
    from: HTMLElement
    oldIndex?: number
    newIndex?: number
  }
  export interface SortableOptions {
    handle?: string
    animation?: number
    onEnd?: (evt: SortableEvent) => void
  }
  export default class Sortable {
    static create(el: HTMLElement, options?: SortableOptions): Sortable
    destroy(): void
  }
}
