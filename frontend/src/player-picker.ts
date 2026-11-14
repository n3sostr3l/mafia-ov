import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnChanges,
} from "@angular/core";
export interface PlayerOption {
  id: string;
  name: string;
  photo?: string | null;
  disabled?: boolean;
}
@Component({
  selector: "app-player-picker",
  standalone: true,
  template: `<div class="player-picker">
    <input
      [attr.aria-label]="label"
      [placeholder]="label"
      [value]="query"
      (input)="type($event)"
      (keydown)="key($event)"
      (focus)="open = true"
      (blur)="open = false"
      autocomplete="off"
      role="combobox"
      [attr.aria-expanded]="open"
      aria-autocomplete="both"
    />
    @if (open && matches().length) {
      <div class="picker-options" role="listbox">
        @for (p of matches(); track p.id) {
          <button
            type="button"
            role="option"
            [attr.aria-selected]="value === p.id"
            [disabled]="p.disabled"
            (mousedown)="$event.preventDefault()"
            (click)="choose(p)"
          >
            @if (p.photo) {
              <img [src]="'/api/v1/uploads/' + p.photo" alt="" />
            }
            {{ p.name }}
          </button>
        }
      </div>
    }
  </div>`,
})
export class PlayerPickerComponent implements OnChanges {
  @Input() options: PlayerOption[] = [];
  @Input() value: string | null = null;
  @Input() label = "Ник игрока";
  @Output() valueChange = new EventEmitter<string | null>();
  query = "";
  prefix = "";
  open = false;
  private cycle = -1;
  ngOnChanges() {
    this.query = this.options.find((p) => p.id === this.value)?.name ?? "";
  }
  matches() {
    const q = this.prefix.trim().toLocaleLowerCase();
    return this.options.filter((p) => p.name.toLocaleLowerCase().includes(q));
  }
  choose(p: PlayerOption) {
    if (p.disabled) return;
    this.query = p.name;
    this.value = p.id;
    this.valueChange.emit(p.id);
    this.open = false;
  }
  type(event: Event) {
    const input = event.target as HTMLInputElement;
    this.prefix = input.value;
    this.query = input.value;
    this.cycle = -1;
    this.open = true;
    if (!this.query) {
      this.value = null;
      this.valueChange.emit(null);
      return;
    }
    const first = this.matches().find(
      (p) =>
        !p.disabled &&
        p.name.toLocaleLowerCase().startsWith(this.prefix.toLocaleLowerCase()),
    );
    if (first && (event as InputEvent).inputType !== "deleteContentBackward") {
      const length = this.prefix.length;
      this.query = first.name;
      input.value = first.name;
      input.setSelectionRange(length, first.name.length);
      this.value = first.id;
      this.valueChange.emit(first.id);
    }
  }
  key(event: KeyboardEvent) {
    const items = this.matches().filter((p) => !p.disabled);
    if (
      (event.key === "Tab" ||
        event.key === "ArrowDown" ||
        event.key === "ArrowUp") &&
      this.open &&
      this.prefix &&
      items.length
    ) {
      event.preventDefault();
      this.cycle =
        (this.cycle +
          (event.shiftKey || event.key === "ArrowUp" ? items.length - 1 : 1)) %
        items.length;
      const p = items[this.cycle];
      this.query = p.name;
      this.value = p.id;
      this.valueChange.emit(p.id);
      const input = event.target as HTMLInputElement;
      input.value = p.name;
      input.setSelectionRange(this.prefix.length, p.name.length);
    } else if (event.key === "Enter" || event.key === "Escape") {
      event.preventDefault();
      this.open = false;
    }
  }
}
