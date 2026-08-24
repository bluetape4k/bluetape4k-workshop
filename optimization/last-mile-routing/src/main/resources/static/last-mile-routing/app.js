(() => {
  "use strict";

  const status = document.getElementById("status");
  const summary = document.getElementById("summary");
  const map = document.getElementById("map");
  const unassigned = document.getElementById("unassigned");

  const text = (value) => document.createTextNode(String(value));
  const setStatus = (value) => { status.textContent = String(value); };

  function addSummary(label, value) {
    const term = document.createElement("dt");
    const detail = document.createElement("dd");
    term.appendChild(text(label));
    detail.appendChild(text(value));
    summary.append(term, detail);
  }

  function render(plan) {
    summary.replaceChildren();
    map.replaceChildren();
    unassigned.replaceChildren();
    addSummary("계획", `${plan.planId} / revision ${plan.revision}`);
    addSummary("상태", plan.state);
    addSummary("matrix", plan.matrixRevision);
    addSummary("점수", `${plan.score.hardScore} / ${plan.score.softScore}`);

    plan.routes.forEach((route) => {
      const points = route.polyline.map((point) => `${point.x},${point.y}`).join(" ");
      const line = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
      line.setAttribute("class", "route");
      line.setAttribute("points", points);
      line.setAttribute("aria-label", `vehicle ${route.vehicleId}; capacity ${route.capacity}; skills ${route.skills.join(",")}`);
      map.appendChild(line);
      route.stops.forEach((stop) => {
        const marker = document.createElementNS("http://www.w3.org/2000/svg", "circle");
        marker.setAttribute("class", "stop");
        const point = route.polyline.find((candidate) => candidate.coordinateId === stop.coordinateId);
        if (point) {
          marker.setAttribute("cx", point.x);
          marker.setAttribute("cy", point.y);
        }
        marker.setAttribute("r", stop.pinned ? "9" : "6");
        marker.setAttribute("aria-label", `${stop.kind} ${stop.jobId}; ETA ${stop.eta}; window ${stop.pickupWindowStart || ""}-${stop.deliveryWindowEnd || ""}`);
        map.appendChild(marker);
      });
    });

    plan.unassigned.forEach((item) => {
      const li = document.createElement("li");
      li.appendChild(text(`${item.jobId}: ${item.reason}`));
      unassigned.appendChild(li);
    });
    setStatus("계획을 표시했습니다.");
  }

  async function load() {
    try {
      const response = await fetch("/api/last-mile-routing/plans/last-mile-demo", {
        headers: { Accept: "application/json" },
      });
      if (response.status === 404) {
        setStatus("아직 계획이 없습니다.");
        return;
      }
      if (!response.ok) throw new Error("plan query failed");
      render(await response.json());
    } catch (error) {
      setStatus("계획을 불러오지 못했습니다.");
    }
  }

  load();
})();
