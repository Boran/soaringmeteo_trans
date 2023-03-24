import { Layer, ReactiveComponents } from './Layer';

export const noLayer : Layer = {
  key: 'none',
  name: 'None',
  title: 'Map only',
  reactiveComponents(): ReactiveComponents {
    return {
      renderer: () => ({
        renderPoint(): void {}
      }),
      summarizer: () => ({
        async summary(): Promise<Array<[string, string]> | undefined> {
          return []
        }
      }),
      mapKey: <div />,
      help: <p>This layer just shows the map.</p>
    }
  }
};
