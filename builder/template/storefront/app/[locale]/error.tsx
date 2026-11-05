'use client';

export default function Failure({reset}: {reset: () => void}) {
  return <div className="notice error" role="alert"><p>⚠</p><button onClick={reset}>↻</button></div>;
}
