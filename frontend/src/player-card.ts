import { Component, Input, OnChanges } from "@angular/core";
import { Player, Role, State, photoId, roleLabel, roleColor } from "./model";
import { IconComponent } from "./icon";
@Component({
  selector: "app-player-card",
  standalone: true,
  imports: [IconComponent],
  template: `
    <div
      class="player-visual"
      [class.eliminated]="player.status !== 'ALIVE'"
      [class.special-role]="player.role === 'SHERIFF'"
      [class.hidden-role]="player.role === 'UNKNOWN'"
      [class.speaking]="state.speaker === player.seat"
      [style.--role-color]="color(player.role)"
    >
      <div class="portrait" [class.has-image]="image">
        @if (image) {
          <img [src]="image" [alt]="player.name" (error)="photoFailed = true" />
        } @else {
          <div class="avatar-art" [attr.data-variant]="player.seat % 5">
            <div class="avatar-shoulders"></div>
            <div class="avatar-head"></div>
            <span>{{ player.name.slice(0, 1) }}</span>
          </div>
        }
        <span class="seat-number">{{
          player.seat < 10 ? "0" + player.seat : player.seat
        }}</span>
      </div>
      <div class="player-caption">
        <strong>{{ player.name }}</strong
        ><span>{{ roleLabel(player.role) }}</span>
      </div>
      @if (player.status === "SHOT") {
        <span
          class="event-icon shot"
          animate.leave="event-leave"
          title="Застрелен"
          ><app-icon name="target"
        /></span>
      }
      @if (player.status === "VOTED") {
        <span
          class="event-icon voted"
          animate.leave="event-leave"
          title="Выбыл по голосованию"
          ><app-icon name="vote"
        /></span>
      }
      @if (state.speaker === player.seat) {
        <span class="speaking-indicator"><i></i><i></i><i></i><i></i></span>
      }
    </div>
    <div class="player-extras">
      @if (state.firstDeparted === player.seat) {
        <span class="pu-block" title="Первый ушедший">ПУ</span>
      }
      @if (player.testament; as t) {
        @if (t.mode === "WHEEL") {
          <span class="wheel-block"
            ><app-icon name="wheel" />{{ t.wheel }}</span
          >
        } @else {
          @for (pick of t.colors; track pick.seat) {
            <span class="color-block" [style.background]="color(pick.color)">{{
              pick.seat
            }}</span>
          }
        }
      }
    </div>
  `,
})
export class PlayerCardComponent implements OnChanges {
  @Input({ required: true }) player!: Player;
  @Input({ required: true }) state!: State;
  @Input() gameId = "";
  @Input() token = "";
  @Input() publicView = false;
  photoFailed = false;
  roleLabel = roleLabel;
  ngOnChanges() {
    this.photoFailed = false;
  }
  get image() {
    const id = photoId(this.state, this.player);
    return !id || this.photoFailed
      ? null
      : this.publicView
        ? "/api/v1/public/games/" +
          this.gameId +
          "/images/" +
          id +
          "?token=" +
          encodeURIComponent(this.token)
        : "/api/v1/uploads/" + id;
  }
  color(role: Role) {
    return roleColor(this.state.theme, role);
  }
}
