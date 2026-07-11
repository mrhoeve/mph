import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from '../api-base-url';
import { TestStreamService } from './test-stream-service';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;

  constructor(readonly url: string) { FakeEventSource.instances.push(this); }
  close(): void { this.closed = true; }
}

describe('TestStreamService', () => {
  const originalEventSource = globalThis.EventSource;
  let service: TestStreamService;

  beforeEach(() => {
    FakeEventSource.instances = [];
    globalThis.EventSource = FakeEventSource as unknown as typeof EventSource;
    TestBed.configureTestingModule({ providers: [{ provide: API_BASE_URL, useValue: '/backend' }] });
    service = TestBed.inject(TestStreamService);
  });

  afterEach(() => globalThis.EventSource = originalEventSource);

  it('publishes server messages and closes when unsubscribed', () => {
    const values: string[] = [];
    const subscription = service.currentTime$().subscribe(value => values.push(value));
    const source = FakeEventSource.instances[0];

    source.onmessage?.(new MessageEvent('message', { data: '12:34:56' }));

    expect(source.url).toBe('/backend/api/test');
    expect(values).toEqual(['12:34:56']);
    subscription.unsubscribe();
    expect(source.closed).toBe(true);
  });

  it('propagates errors and closes the connection', () => {
    const errors: Event[] = [];
    service.currentTime$().subscribe({ error: error => errors.push(error) });
    const source = FakeEventSource.instances[0];
    const failure = new Event('connection-lost');

    source.onerror?.(failure);

    expect(errors).toEqual([failure]);
    expect(source.closed).toBe(true);
  });
});
