import '@testing-library/jest-dom/vitest';

class ResizeObserverStub implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

Object.defineProperty(globalThis, 'ResizeObserver', {
  configurable: true,
  value: ResizeObserverStub
});

Object.defineProperty(globalThis, 'matchMedia', {
  configurable: true,
  value: (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false
  })
});

const nativeGetComputedStyle = globalThis.getComputedStyle.bind(globalThis);
Object.defineProperty(globalThis, 'getComputedStyle', {
  configurable: true,
  value: (element: Element) => nativeGetComputedStyle(element)
});
