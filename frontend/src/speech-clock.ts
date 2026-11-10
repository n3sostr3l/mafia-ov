import { Component, Input, OnDestroy, signal } from "@angular/core";
import { State } from "./model";

@Component({
  selector: "app-speech-clock",
  standalone: true,
  template: `
    @if (state.speaker !== null) {
      <div class="speech-clock" [class.paused]="state.speechEndsAt === null">
        <div class="speech-clock-label">
          <span>Говорит № {{ state.speaker }}</span
          ><strong
            >{{ seconds() }} с{{
              state.speechEndsAt === null ? " · пауза" : ""
            }}</strong
          >
        </div>
        <div
          class="speech-track"
          role="progressbar"
          aria-label="Оставшееся время речи"
          aria-valuemin="0"
          aria-valuemax="60"
          [attr.aria-valuenow]="seconds()"
        >
          <span [style.width.%]="remaining() / 600"></span>
        </div>
      </div>
    }
  `,
})
export class SpeechClockComponent implements OnDestroy {
  @Input({ required: true }) state!: State;
  private now = signal(Date.now());
  private timer = setInterval(() => this.now.set(Date.now()), 100);
  remaining() {
    return Math.max(
      0,
      Math.min(
        60000,
        this.state.speechEndsAt === null
          ? this.state.speechRemainingMs
          : this.state.speechEndsAt - this.now(),
      ),
    );
  }
  seconds() {
    return Math.ceil(this.remaining() / 1000);
  }
  ngOnDestroy() {
    clearInterval(this.timer);
  }
}
