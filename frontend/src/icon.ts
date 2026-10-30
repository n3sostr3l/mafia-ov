import { Component, Input } from "@angular/core";
const paths: Record<string, string> = {
  grid: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  screen: "M3 4h18v12H3z M8 21h8 M12 16v5",
  settings:
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2",
  trophy:
    "M7 3h10v7a5 5 0 0 1-10 0z M7 5H3v3a4 4 0 0 0 4 4 M17 5h4v3a4 4 0 0 1-4 4 M12 15v6 M8 21h8",
  history: "M3 11a9 9 0 1 1 2.5 7 M3 4v7h7 M12 7v5l3 2",
  chevron: "M9 5l7 7-7 7",
  down: "M6 9l6 6 6-6",
  plus: "M12 4v16 M4 12h16",
  close: "M6 6l12 12 M6 18L18 6",
  mic: "M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0z M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8",
  target:
    "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16 M12 1v6 M12 17v6 M1 12h6 M17 12h6 M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4",
  vote: "M4 12l4-6 4 3 4-3 4 6 M3 12h18v9H3z M9 15h6 M9 2l3 3 3-3",
  eye: "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12 M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6",
  hidden:
    "M3 3l18 18 M10 5h2c6 0 10 7 10 7a19 19 0 0 1-4 4 M6 6a19 19 0 0 0-4 6s4 7 10 7a11 11 0 0 0 5-1 M10 10a3 3 0 0 0 4 4",
  copy: "M8 8h13v13H8z M16 8V3H3v13h5",
  external: "M14 3h7v7 M21 3L10 14 M10 3H3v18h18v-7",
  check: "M4 12l5 5L20 6",
  search: "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6",
  shield: "M12 2l9 4v6c0 6-9 10-9 10S3 18 3 12V6z M8 12l3 3 5-6",
  logout: "M9 3H3v18h6 M14 7l5 5-5 5 M8 12h13",
  help: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20 M9 8a3 3 0 0 1 6 1c0 2-3 2-3 5 M12 17h.01",
  sun: "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10 M12 1v2 M12 21v2 M1 12h2 M21 12h2 M4 4l2 2 M18 18l2 2 M4 20l2-2 M18 6l2-2",
  moon: "M20 15A9 9 0 0 1 9 4a9 9 0 1 0 11 11",
  upload: "M12 16V3 M7 8l5-5 5 5 M3 15v6h18v-6",
  link: "M10 14l4-4 M8 16l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0 M16 8l1-1a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0",
  alert: "M12 3l10 18H2z M12 9v5 M12 17h.01",
  menu: "M3 6h18 M3 12h18 M3 18h18",
  play: "M8 4l13 8-13 8z",
  undo: "M8 4l-6 6 6 6 M2 10h12a7 7 0 0 1 7 7v3",
  camera: "M3 6h5l2-3h4l2 3h5v15H3z M12 10a4 4 0 1 0 0 8 4 4 0 0 0 0-8",
  users:
    "M8 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M1 22v-5a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v5 M16 4a4 4 0 0 1 0 8 M18 15a5 5 0 0 1 5 5v2",
  wheel:
    "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M3 11h18 M12 11l-5 9 M12 11l5 9 M12 9a2 2 0 1 0 0 4 2 2 0 0 0 0-4",
};
@Component({
  selector: "app-icon",
  standalone: true,
  template:
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="path"/></svg>',
})
export class IconComponent {
  @Input() name = "grid";
  get path() {
    return paths[this.name] || paths["grid"];
  }
}
