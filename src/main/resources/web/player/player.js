// Enhances the server-rendered <podcast-player>: chapter links seek the native <audio>,
// and the current chapter gets aria-current="true". Without JS the links point at #t= and the audio still plays.
class PodcastPlayer extends HTMLElement {
  connectedCallback() {
    const audio = this.querySelector("audio");
    const links = Array.from(this.querySelectorAll("[data-start]"));
    if (!audio || links.length === 0) return;
    links.forEach((link) => link.addEventListener("click", (e) => {
      e.preventDefault();
      audio.currentTime = Number(link.dataset.start);
      audio.play();
    }));
    audio.addEventListener("timeupdate", () => {
      let current = links[0];
      for (const link of links) if (Number(link.dataset.start) <= audio.currentTime + 0.25) current = link;
      links.forEach((link) => link.toggleAttribute("aria-current", link === current));
    });
  }
}
customElements.define("podcast-player", PodcastPlayer);
