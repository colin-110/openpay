import { useId } from "react";

/**
 * Hand-drawn SVG rather than a charting library.
 *
 * <p>Two reasons. A chart library is ~50 kB for two charts that each need one path and a gradient,
 * and it would decide the visual language of the console for us. This way the axis, the grid and
 * the endpoint marker match the rest of the design exactly.
 */
export type Bucket = { label: string; value: number };

/** Volume over time: filled area, faint grid, and the most recent point called out. */
export function AreaChart({
  buckets,
  format,
  height = 132,
}: {
  buckets: Bucket[];
  format: (value: number) => string;
  height?: number;
}) {
  const gradientId = useId();
  if (buckets.length === 0) return <p className="muted">Nothing to plot yet.</p>;

  const width = 100;
  const peak = Math.max(...buckets.map((b) => b.value), 1);
  // A single bucket has no line to draw, so give it a flat two-point series rather than a dot
  // floating in space.
  const points = buckets.length === 1 ? [buckets[0], buckets[0]] : buckets;
  const step = width / (points.length - 1);

  const coords = points.map((bucket, index) => ({
    x: index * step,
    // 6% headroom so the peak never touches the top edge and read as clipped.
    y: 100 - (bucket.value / peak) * 94,
  }));

  const line = coords.map((c, i) => `${i === 0 ? "M" : "L"}${c.x.toFixed(2)} ${c.y.toFixed(2)}`).join(" ");
  const area = `${line} L${width} 100 L0 100 Z`;
  const last = coords[coords.length - 1];

  return (
    <div className="chart" style={{ height }}>
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="chart-svg">
        <defs>
          <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--brand)" stopOpacity="0.22" />
            <stop offset="100%" stopColor="var(--brand)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {[25, 50, 75].map((y) => (
          <line key={y} x1="0" y1={y} x2="100" y2={y} className="chart-grid" vectorEffect="non-scaling-stroke" />
        ))}
        <path d={area} fill={`url(#${gradientId})`} />
        <path d={line} className="chart-line" vectorEffect="non-scaling-stroke" />
      </svg>
      {/* Outside the stretched viewBox so the marker stays a circle rather than an ellipse. */}
      <span className="chart-endpoint" style={{ left: `${last.x}%`, top: `${last.y}%` }} />
      <div className="chart-axis">
        <span>{buckets[0].label}</span>
        <span className="chart-peak">peak {format(peak)}</span>
        <span>{buckets[buckets.length - 1].label}</span>
      </div>
    </div>
  );
}

/** A proportion over time. Fixed 0–100 scale, because a rescaled success rate flatters itself. */
export function RateChart({ buckets, height = 132 }: { buckets: Bucket[]; height?: number }) {
  if (buckets.length === 0) return <p className="muted">No payment has reached an outcome yet.</p>;

  const width = 100;
  const points = buckets.length === 1 ? [buckets[0], buckets[0]] : buckets;
  const step = width / (points.length - 1);
  const coords = points.map((bucket, index) => ({
    x: index * step,
    y: 100 - Math.max(0, Math.min(100, bucket.value)),
  }));
  const line = coords.map((c, i) => `${i === 0 ? "M" : "L"}${c.x.toFixed(2)} ${c.y.toFixed(2)}`).join(" ");
  const last = coords[coords.length - 1];

  return (
    <div className="chart" style={{ height }}>
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="chart-svg">
        {[0, 25, 50, 75].map((y) => (
          <line key={y} x1="0" y1={y} x2="100" y2={y} className="chart-grid" vectorEffect="non-scaling-stroke" />
        ))}
        <path d={line} className="chart-line ok" vectorEffect="non-scaling-stroke" />
      </svg>
      <span className="chart-endpoint ok" style={{ left: `${last.x}%`, top: `${last.y}%` }} />
      <div className="chart-axis">
        <span>{buckets[0].label}</span>
        <span className="chart-peak">0–100%</span>
        <span>{buckets[buckets.length - 1].label}</span>
      </div>
    </div>
  );
}

/**
 * Groups items into one bucket per day for the last {@code days} days, including days with nothing
 * in them. Dropping empty days would compress the axis and make a quiet week look busy.
 */
export function bucketByDay<T>(
  items: T[],
  days: number,
  at: (item: T) => string,
  reduce: (inBucket: T[]) => number
): Bucket[] {
  const buckets: Bucket[] = [];
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());

  for (let offset = days - 1; offset >= 0; offset--) {
    const from = new Date(startOfToday);
    from.setDate(from.getDate() - offset);
    const to = new Date(from);
    to.setDate(to.getDate() + 1);

    const inBucket = items.filter((item) => {
      const when = new Date(at(item));
      return when >= from && when < to;
    });

    buckets.push({
      label: new Intl.DateTimeFormat("en-IN", { day: "numeric", month: "short" }).format(from),
      value: reduce(inBucket),
    });
  }
  return buckets;
}
